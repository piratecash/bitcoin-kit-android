package io.horizontalsystems.litecoinkit.mweb.daemon

import android.os.SystemClock
import com.piratecash.mwebdandroid.Daemon
import com.piratecash.mwebdandroid.Mwebdandroid
import com.piratecash.mwebdandroid.StringList
import com.piratecash.mwebdandroid.Utxo as NativeUtxo
import com.piratecash.mwebdandroid.UtxoListener
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.MwebError
import io.horizontalsystems.litecoinkit.mweb.MwebSyncState
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo
import timber.log.Timber
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val LOG_TAG = "MwebDaemon"
private const val LOG_OUTPUT_ID_PREFIX_LENGTH = 8
private const val LOG_AGGREGATE_BATCH = 100
private const val LOG_AGGREGATE_INTERVAL_MILLIS = 5_000L

object MwebdAndroidDaemonClientFactory : MwebDaemonClientFactory {
    override fun create(config: MwebDaemonConfig): MwebDaemonClient {
        return MwebdAndroidDaemonClient(config)
    }
}

private class MwebdAndroidDaemonClient(
    private val config: MwebDaemonConfig,
) : MwebDaemonClient {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }
    private val addressIndexes = mutableMapOf<String, Int>()
    private var daemon: Daemon? = null
    private var started = false

    override fun start(statusTimeoutMillis: Long): MwebDaemonStatus {
        val daemon = daemon ?: createDaemon()
        this.daemon = daemon
        if (!started) {
            timeoutCall(statusTimeoutMillis) { daemon.start(PORT_AUTO_SELECT) }
            started = true
        }
        return status(statusTimeoutMillis)
    }

    override fun stop() {
        daemon?.stop()
        daemon = null
        started = false
    }

    override fun status(statusTimeoutMillis: Long): MwebDaemonStatus {
        val status = timeoutCall(statusTimeoutMillis) { requireDaemon().status() }
        return MwebDaemonStatus(
            syncState = MwebSyncState(
                blockHeaderHeight = status.blockHeaderHeight().toInt(),
                mwebHeaderHeight = status.mwebHeaderHeight().toInt(),
                mwebUtxosHeight = status.mwebUtxosHeight().toInt(),
            ),
            nativeVersion = NATIVE_VERSION,
            blockTime = status.blockTime(),
        )
    }

    override fun addresses(fromIndex: Int, toIndex: Int): List<String> {
        val addresses = requireDaemon()
            .addresses(
                config.accountKeys.scanSecret,
                config.accountKeys.spendPublicKey,
                fromIndex.toLong(),
                toIndex.toMwebdExclusiveToIndex(),
            )
            .toKotlinList()
        addresses.forEachIndexed { offset, address ->
            addressIndexes[address] = fromIndex + offset
        }
        return addresses
    }

    override fun utxos(
        fromHeight: Int,
        onUtxo: (MwebUtxo) -> Unit,
        onReplayComplete: (Int) -> Unit,
        onComplete: () -> Unit,
        onError: (Throwable) -> Unit,
    ): Closeable {
        val startedAt = SystemClock.elapsedRealtime()
        val aggregator = UtxoStreamAggregator(startedAt)
        Timber.tag(LOG_TAG).d("utxos.subscribe fromHeight=$fromHeight")
        val subscription = requireDaemon().subscribeUtxos(
            fromHeight.toLong(),
            config.accountKeys.scanSecret,
            MwebUtxoListenerAdapter(
                aggregator = aggregator,
                startedAt = startedAt,
                addressIndex = ::addressIndex,
                onUtxo = onUtxo,
                onReplayComplete = onReplayComplete,
                onComplete = onComplete,
                onError = onError,
            ),
        )
        return Closeable { subscription.close() }
    }

    override fun spent(outputIds: List<String>): List<String> {
        if (outputIds.isEmpty()) return emptyList()
        return requireDaemon().spent(outputIds.joinToString(separator = ",")).toKotlinList()
    }

    override fun create(rawTransaction: ByteArray, feeRate: Int, dryRun: Boolean): MwebCreateResult {
        val daemon = requireDaemon()
        logCreate(dryRun, "mwebd.create started: rawBytes=${rawTransaction.size}, feeRate=$feeRate, dryRun=$dryRun")
        try {
            val result = daemon.create(
                rawTransaction,
                config.accountKeys.scanSecret,
                config.accountKeys.spendSecret,
                feeRate.toLong() * FEE_RATE_KB_MULTIPLIER,
                dryRun,
            )
            val rawTx = result.rawTx()
            val outputIds = result.outputIds().toKotlinList()
            logCreate(dryRun, "mwebd.create finished: rawBytes=${rawTx.size}, outputIds=${outputIds.size}, dryRun=$dryRun")
            return MwebCreateResult(
                rawTransaction = rawTx,
                outputIds = outputIds,
            )
        } catch (error: Throwable) {
            logCreateFailure(dryRun, error)
            throw error
        }
    }

    override fun broadcast(rawTransaction: ByteArray): String {
        val daemon = requireDaemon()
        Timber.tag(LOG_TAG).d("mwebd.broadcast started: rawBytes=${rawTransaction.size}")
        try {
            val transactionHash = daemon.broadcast(rawTransaction).txId()
            Timber.tag(LOG_TAG).d("mwebd.broadcast finished: tx=$transactionHash")
            return transactionHash
        } catch (error: Throwable) {
            Timber.tag(LOG_TAG).d(error, "mwebd.broadcast failed")
            throw error
        }
    }

    private fun logCreate(dryRun: Boolean, message: String) {
        if (!dryRun) {
            Timber.tag(LOG_TAG).d(message)
        }
    }

    private fun logCreateFailure(dryRun: Boolean, error: Throwable) {
        if (!dryRun) {
            Timber.tag(LOG_TAG).d(error, "mwebd.create failed")
        }
    }

    private fun createDaemon(): Daemon {
        config.dataDir.mkdirs()
        val chain = chain()
        val dataDir = config.dataDir.absolutePath
        val peerAddress = config.peerAddress.orEmpty()
        val restoreCheckpoint = config.restoreCheckpoint
        return if (restoreCheckpoint.isNullOrEmpty()) {
            newDaemon(chain, dataDir, peerAddress)
        } else {
            newDaemonWithRestoreCheckpoint(chain, dataDir, peerAddress, restoreCheckpoint)
        }
    }

    private fun newDaemon(chain: String, dataDir: String, peerAddress: String): Daemon {
        return Mwebdandroid.newDaemon(chain, dataDir, peerAddress, PROXY_DISABLED)
    }

    private fun newDaemonWithRestoreCheckpoint(
        chain: String,
        dataDir: String,
        peerAddress: String,
        restoreCheckpoint: String,
    ): Daemon {
        val method = try {
            Mwebdandroid::class.java.getMethod(
                "newDaemonWithRestoreCheckpoint",
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
            )
        } catch (error: NoSuchMethodException) {
            Timber.tag(LOG_TAG).d("mwebd checkpoint bootstrap is unavailable in this native library")
            return newDaemon(chain, dataDir, peerAddress)
        }

        return try {
            method.invoke(null, chain, dataDir, peerAddress, PROXY_DISABLED, restoreCheckpoint) as? Daemon
                ?: run {
                    Timber.tag(LOG_TAG).d("mwebd checkpoint bootstrap returned unexpected daemon type")
                    newDaemon(chain, dataDir, peerAddress)
                }
        } catch (error: IllegalAccessException) {
            Timber.tag(LOG_TAG).d(error, "mwebd checkpoint bootstrap method is inaccessible")
            newDaemon(chain, dataDir, peerAddress)
        } catch (error: InvocationTargetException) {
            throw error.cause ?: error
        }
    }

    private fun chain(): String = when (config.networkType) {
        LitecoinKit.NetworkType.MainNet -> Mwebdandroid.ChainMainnet
        LitecoinKit.NetworkType.TestNet -> Mwebdandroid.ChainTestnet
    }

    private fun requireDaemon(): Daemon {
        return daemon ?: throw MwebError.NativeUnavailable()
    }

    private fun addressIndex(address: String): Int {
        addressIndexes[address]?.let { return it }
        addresses(0, ADDRESS_DISCOVERY_LIMIT)
        return addressIndexes[address]
            ?: throw MwebError.SyncFailure(IllegalStateException("Unknown MWEB address index"))
    }

    private fun <T> timeoutCall(timeoutMillis: Long, block: () -> T): T {
        val future = executor.submit<T> { block() }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun StringList.toKotlinList(): List<String> {
        return (0 until len()).map { index -> get(index) }
    }

    private companion object {
        const val ADDRESS_DISCOVERY_LIMIT = 100
        const val FEE_RATE_KB_MULTIPLIER = 1_000L
        const val NATIVE_VERSION = "ltcmweb/mwebd v0.1.19, mwebd-android v0.1.19-pcash.8"
        const val PORT_AUTO_SELECT = 0L
        const val PROXY_DISABLED = ""
        const val THREAD_NAME = "litecoin-mwebd"
    }
}

internal class UtxoStreamAggregator(private val startedAt: Long) {
    private var count = 0
    private var lastHeight = 0L
    private var lastEmittedAt = startedAt

    fun observe(height: Long) {
        count += 1
        lastHeight = height
        val now = SystemClock.elapsedRealtime()
        if (count % LOG_AGGREGATE_BATCH == 0 || now - lastEmittedAt >= LOG_AGGREGATE_INTERVAL_MILLIS) {
            emit(now)
        }
    }

    fun flush() {
        if (count == 0) return
        emit(SystemClock.elapsedRealtime())
    }

    private fun emit(now: Long) {
        Timber.tag(LOG_TAG).d("utxos.batch received=$count lastHeight=$lastHeight uptime=${now - startedAt}ms")
        lastEmittedAt = now
    }
}

internal class MwebUtxoListenerAdapter(
    private val aggregator: UtxoStreamAggregator,
    private val startedAt: Long,
    private val addressIndex: (String) -> Int,
    private val onUtxo: (MwebUtxo) -> Unit,
    private val onReplayComplete: (Int) -> Unit,
    private val onComplete: () -> Unit,
    private val onError: (Throwable) -> Unit,
) : UtxoListener {
    override fun onUtxo(utxo: NativeUtxo) {
        if (utxo.isMwebInitMarker()) {
            Timber.tag(LOG_TAG).d("utxos.initMarker")
            return
        }

        val outputId = utxo.outputId()
        val height = utxo.height()
        Timber.tag(LOG_TAG).v("utxos.onUtxo outputId=${outputId.logPrefix()} height=$height")
        aggregator.observe(height)
        try {
            onUtxo(utxo.toMwebUtxo())
        } catch (error: Throwable) {
            Timber.tag(LOG_TAG).w(error, "utxos.toMwebUtxo failed outputId=${outputId.logPrefix()} height=$height")
            onError(error)
        }
    }

    override fun onReplayComplete(height: Long) {
        aggregator.flush()
        Timber.tag(LOG_TAG).d("utxos.replayComplete height=$height uptime=${startedAt.uptimeMillis()}ms")
        onReplayComplete(height.toInt())
    }

    override fun onError(message: String) {
        aggregator.flush()
        Timber.tag(LOG_TAG).w("utxos.onError uptime=${startedAt.uptimeMillis()}ms message=$message")
        onError(MwebError.SyncFailure(IllegalStateException(message)))
    }

    override fun onComplete() {
        aggregator.flush()
        Timber.tag(LOG_TAG).d("utxos.onComplete uptime=${startedAt.uptimeMillis()}ms")
        onComplete()
    }

    private fun NativeUtxo.toMwebUtxo(): MwebUtxo {
        val address = address()
        return MwebUtxo(
            outputId = outputId(),
            address = address,
            addressIndex = addressIndex(address),
            value = value(),
            height = height().toInt(),
            blockTime = blockTime(),
            spent = false,
        )
    }
}

private fun Long.uptimeMillis(): Long = SystemClock.elapsedRealtime() - this

private fun String.logPrefix(): String = take(LOG_OUTPUT_ID_PREFIX_LENGTH)

internal fun Int.toMwebdExclusiveToIndex(): Long = toLong() + 1L

internal fun isMwebInitMarker(
    outputId: String,
    address: String,
    value: Long,
    height: Long,
    blockTime: Long,
): Boolean {
    return outputId.isEmpty() &&
        address.isEmpty() &&
        value == 0L &&
        height == 0L &&
        blockTime == 0L
}

private fun NativeUtxo.isMwebInitMarker(): Boolean {
    return isMwebInitMarker(
        outputId = outputId(),
        address = address(),
        value = value(),
        height = height(),
        blockTime = blockTime(),
    )
}

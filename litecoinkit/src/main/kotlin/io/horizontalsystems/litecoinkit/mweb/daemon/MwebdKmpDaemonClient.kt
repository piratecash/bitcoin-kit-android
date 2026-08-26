package io.horizontalsystems.litecoinkit.mweb.daemon

import co.touchlab.kermit.Logger
import com.piratecash.mwebd.Mwebd
import com.piratecash.mwebd.MwebdAccountKeys
import com.piratecash.mwebd.MwebdChain
import com.piratecash.mwebd.MwebdConfig
import com.piratecash.mwebd.MwebdDaemon
import com.piratecash.mwebd.MwebdUtxo as NativeUtxo
import com.piratecash.mwebd.MwebdUtxoListener
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.MwebError
import io.horizontalsystems.litecoinkit.mweb.MwebSyncState
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo
import java.io.Closeable

private const val LOG_TAG = "MwebDaemon"
private val log = Logger.withTag(LOG_TAG)
private const val LOG_OUTPUT_ID_PREFIX_LENGTH = 8
private const val LOG_AGGREGATE_BATCH = 100
private const val LOG_AGGREGATE_INTERVAL_MILLIS = 5_000L

object MwebdKmpDaemonClientFactory : MwebDaemonClientFactory {
    override fun create(config: MwebDaemonConfig): MwebDaemonClient {
        return MwebdKmpDaemonClient(config)
    }
}

private class MwebdKmpDaemonClient(
    private val config: MwebDaemonConfig,
) : MwebDaemonClient {
    private val addressIndexes = mutableMapOf<String, Int>()
    private var daemon: MwebdDaemon? = null
    private var started = false

    override fun start(statusTimeoutMillis: Long): MwebDaemonStatus {
        val daemon = daemon ?: createDaemon()
        this.daemon = daemon
        if (!started) {
            daemon.start(statusTimeoutMillis)
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
        val status = requireDaemon().status(statusTimeoutMillis)
        return MwebDaemonStatus(
            syncState = MwebSyncState(
                blockHeaderHeight = status.blockHeaderHeight,
                mwebHeaderHeight = status.mwebHeaderHeight,
                mwebUtxosHeight = status.mwebUtxosHeight,
            ),
            nativeVersion = status.nativeVersion,
            blockTime = status.blockTime,
        )
    }

    override fun addresses(fromIndex: Int, toIndex: Int): List<String> {
        val addresses = requireDaemon().addresses(fromIndex, toIndex)
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
        val startedAt = monotonicMillis()
        val aggregator = UtxoStreamAggregator(startedAt)
        log.d { "utxos.subscribe fromHeight=$fromHeight" }
        val subscription = requireDaemon().subscribeUtxos(
            fromHeight,
            MwebUtxoListenerAdapter(
                aggregator = aggregator,
                startedAt = startedAt,
                addressIndex = ::addressIndex,
                handleUtxo = onUtxo,
                handleReplayComplete = onReplayComplete,
                handleComplete = onComplete,
                handleError = onError,
            ),
        )
        return Closeable { subscription.close() }
    }

    override fun spent(outputIds: List<String>): List<String> {
        if (outputIds.isEmpty()) return emptyList()
        return requireDaemon().spent(outputIds)
    }

    override fun create(rawTransaction: ByteArray, feeRate: Int, dryRun: Boolean): MwebCreateResult {
        logCreate(dryRun, "mwebd.create started: rawBytes=${rawTransaction.size}, feeRate=$feeRate, dryRun=$dryRun")
        try {
            val result = requireDaemon().create(rawTransaction, feeRate, dryRun)
            val rawTx = result.rawTransaction
            val outputIds = result.outputIds
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
        log.d { "mwebd.broadcast started: rawBytes=${rawTransaction.size}" }
        try {
            val transactionHash = requireDaemon().broadcast(rawTransaction)
            log.d { "mwebd.broadcast finished: tx=$transactionHash" }
            return transactionHash
        } catch (error: Throwable) {
            log.d(error) { "mwebd.broadcast failed" }
            throw error
        }
    }

    private fun logCreate(dryRun: Boolean, message: String) {
        if (!dryRun) {
            log.d { message }
        }
    }

    private fun logCreateFailure(dryRun: Boolean, error: Throwable) {
        if (!dryRun) {
            log.d(error) { "mwebd.create failed" }
        }
    }

    private fun createDaemon(): MwebdDaemon {
        config.dataDir.mkdirs()
        return Mwebd.create(
            MwebdConfig(
                chain = chain(),
                dataDir = config.dataDir.absolutePath,
                accountKeys = MwebdAccountKeys(
                    scanSecret = config.accountKeys.scanSecret,
                    spendSecret = config.accountKeys.spendSecret,
                    spendPublicKey = config.accountKeys.spendPublicKey,
                ),
                peerAddress = config.peerAddress,
                restoreCheckpoint = config.restoreCheckpoint,
            )
        )
    }

    private fun chain(): MwebdChain = when (config.networkType) {
        LitecoinKit.NetworkType.MainNet -> MwebdChain.Mainnet
        LitecoinKit.NetworkType.TestNet -> MwebdChain.Testnet
    }

    private fun requireDaemon(): MwebdDaemon {
        return daemon ?: throw MwebError.NativeUnavailable()
    }

    private fun addressIndex(address: String): Int {
        addressIndexes[address]?.let { return it }
        addresses(0, ADDRESS_DISCOVERY_LIMIT)
        return addressIndexes[address]
            ?: throw MwebError.SyncFailure(IllegalStateException("Unknown MWEB address index"))
    }

    private companion object {
        const val ADDRESS_DISCOVERY_LIMIT = 100
    }
}

internal class UtxoStreamAggregator(private val startedAt: Long) {
    private var count = 0
    private var lastHeight = 0
    private var lastEmittedAt = startedAt

    fun observe(height: Int) {
        count += 1
        lastHeight = height
        val now = monotonicMillis()
        if (count % LOG_AGGREGATE_BATCH == 0 || now - lastEmittedAt >= LOG_AGGREGATE_INTERVAL_MILLIS) {
            emit(now)
        }
    }

    fun flush() {
        if (count == 0) return
        emit(monotonicMillis())
    }

    private fun emit(now: Long) {
        log.d { "utxos.batch received=$count lastHeight=$lastHeight uptime=${now - startedAt}ms" }
        lastEmittedAt = now
    }
}

internal class MwebUtxoListenerAdapter(
    private val aggregator: UtxoStreamAggregator,
    private val startedAt: Long,
    private val addressIndex: (String) -> Int,
    private val handleUtxo: (MwebUtxo) -> Unit,
    private val handleReplayComplete: (Int) -> Unit,
    private val handleComplete: () -> Unit,
    private val handleError: (Throwable) -> Unit,
) : MwebdUtxoListener {
    override fun onUtxo(utxo: NativeUtxo) {
        if (utxo.isMwebInitMarker()) {
            log.d { "utxos.initMarker" }
            return
        }

        val outputId = utxo.outputId
        val height = utxo.height
        log.v { "utxos.onUtxo outputId=${outputId.logPrefix()} height=$height" }
        aggregator.observe(height)
        try {
            handleUtxo(utxo.toMwebUtxo())
        } catch (error: Throwable) {
            log.w(error) { "utxos.toMwebUtxo failed outputId=${outputId.logPrefix()} height=$height" }
            handleError(error)
        }
    }

    override fun onReplayComplete(height: Int) {
        aggregator.flush()
        log.d { "utxos.replayComplete height=$height uptime=${startedAt.uptimeMillis()}ms" }
        handleReplayComplete(height)
    }

    override fun onError(error: Throwable) {
        aggregator.flush()
        log.w(error) { "utxos.onError uptime=${startedAt.uptimeMillis()}ms" }
        handleError(MwebError.SyncFailure(error))
    }

    override fun onComplete() {
        aggregator.flush()
        log.d { "utxos.onComplete uptime=${startedAt.uptimeMillis()}ms" }
        handleComplete()
    }

    private fun NativeUtxo.toMwebUtxo(): MwebUtxo {
        return MwebUtxo(
            outputId = outputId,
            address = address,
            addressIndex = addressIndex(address),
            value = value,
            height = height,
            blockTime = blockTime,
            spent = false,
        )
    }
}

internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

private fun Long.uptimeMillis(): Long = monotonicMillis() - this

private fun String.logPrefix(): String = take(LOG_OUTPUT_ID_PREFIX_LENGTH)

internal fun isMwebInitMarker(
    outputId: String,
    address: String,
    value: Long,
    height: Int,
    blockTime: Long,
): Boolean {
    return outputId.isEmpty() &&
        address.isEmpty() &&
        value == 0L &&
        height == 0 &&
        blockTime == 0L
}

private fun NativeUtxo.isMwebInitMarker(): Boolean {
    return isMwebInitMarker(
        outputId = outputId,
        address = address,
        value = value,
        height = height,
        blockTime = blockTime,
    )
}

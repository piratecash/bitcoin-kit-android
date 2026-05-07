package io.horizontalsystems.litecoinkit.mweb.daemon

import com.piratecash.mwebdandroid.Daemon
import com.piratecash.mwebdandroid.Mwebdandroid
import com.piratecash.mwebdandroid.StringList
import com.piratecash.mwebdandroid.Utxo as NativeUtxo
import com.piratecash.mwebdandroid.UtxoListener
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.MwebError
import io.horizontalsystems.litecoinkit.mweb.MwebSyncState
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo
import java.io.Closeable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
        onError: (Throwable) -> Unit,
    ): Closeable {
        val subscription = requireDaemon().subscribeUtxos(
            fromHeight.toLong(),
            config.accountKeys.scanSecret,
            object : UtxoListener {
                override fun onUtxo(utxo: NativeUtxo) {
                    try {
                        onUtxo(utxo.toMwebUtxo())
                    } catch (error: Throwable) {
                        onError(error)
                    }
                }

                override fun onError(message: String) {
                    onError(MwebError.SyncFailure(IllegalStateException(message)))
                }

                override fun onComplete() = Unit
            },
        )
        return Closeable { subscription.close() }
    }

    override fun spent(outputIds: List<String>): List<String> {
        if (outputIds.isEmpty()) return emptyList()
        return requireDaemon().spent(outputIds.joinToString(separator = ",")).toKotlinList()
    }

    override fun create(rawTransaction: ByteArray, feeRate: Int, dryRun: Boolean): MwebCreateResult {
        val result = requireDaemon().create(
            rawTransaction,
            config.accountKeys.scanSecret,
            config.accountKeys.spendSecret,
            feeRate.toLong() * FEE_RATE_KB_MULTIPLIER,
            dryRun,
        )
        return MwebCreateResult(
            rawTransaction = result.rawTx(),
            outputIds = result.outputIds().toKotlinList(),
        )
    }

    override fun broadcast(rawTransaction: ByteArray): String {
        return requireDaemon().broadcast(rawTransaction).txId()
    }

    private fun createDaemon(): Daemon {
        config.dataDir.mkdirs()
        return Mwebdandroid.newDaemon(chain(), config.dataDir.absolutePath, config.peerAddress.orEmpty(), PROXY_DISABLED)
    }

    private fun chain(): String = when (config.networkType) {
        LitecoinKit.NetworkType.MainNet -> Mwebdandroid.ChainMainnet
        LitecoinKit.NetworkType.TestNet -> Mwebdandroid.ChainTestnet
    }

    private fun requireDaemon(): Daemon {
        return daemon ?: throw MwebError.NativeUnavailable()
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
        const val NATIVE_VERSION = "ltcmweb/mwebd v0.1.17, mwebd-android v0.1.17-pcash.6"
        const val PORT_AUTO_SELECT = 0L
        const val PROXY_DISABLED = ""
        const val THREAD_NAME = "litecoin-mwebd"
    }
}

internal fun Int.toMwebdExclusiveToIndex(): Long = toLong() + 1L

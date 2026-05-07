package io.horizontalsystems.bitcoinkit.demo

import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.MwebSyncState
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressCodec
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClient
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClientFactory
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonConfig
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonStatus
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebCreateResult
import java.io.Closeable

class DemoMwebDaemonClientFactory : MwebDaemonClientFactory {
    override fun create(config: MwebDaemonConfig): MwebDaemonClient {
        return DemoMwebDaemonClient(config.networkType)
    }
}

private class DemoMwebDaemonClient(
    networkType: LitecoinKit.NetworkType,
) : MwebDaemonClient {
    private val addressCodec = MwebAddressCodec(networkType)
    private var started = false

    override fun start(statusTimeoutMillis: Long): MwebDaemonStatus {
        started = true
        return status()
    }

    override fun stop() {
        started = false
    }

    override fun status(statusTimeoutMillis: Long): MwebDaemonStatus {
        return MwebDaemonStatus(
            syncState = MwebSyncState(
                blockHeaderHeight = if (started) 2_300_000 else 0,
                mwebHeaderHeight = if (started) 2_300_000 else 0,
                mwebUtxosHeight = if (started) 2_300_000 else 0,
            ),
            nativeVersion = "demo",
        )
    }

    override fun addresses(fromIndex: Int, toIndex: Int): List<String> {
        return (fromIndex..toIndex).map { index ->
            addressCodec.encode(
                scanPublicKey = ByteArray(33) { offset -> (index + offset + 1).toByte() },
                spendPublicKey = ByteArray(33) { offset -> (index + offset + 34).toByte() },
            )
        }
    }

    override fun utxos(fromHeight: Int, onUtxo: (MwebUtxo) -> Unit, onError: (Throwable) -> Unit): Closeable {
        return Closeable { }
    }

    override fun spent(outputIds: List<String>): List<String> {
        return emptyList()
    }

    override fun create(rawTransaction: ByteArray, feeRate: Int, dryRun: Boolean): MwebCreateResult {
        return MwebCreateResult(rawTransaction, listOf("demo-output-id"))
    }

    override fun broadcast(rawTransaction: ByteArray): String {
        return "demo-mweb-transaction"
    }
}

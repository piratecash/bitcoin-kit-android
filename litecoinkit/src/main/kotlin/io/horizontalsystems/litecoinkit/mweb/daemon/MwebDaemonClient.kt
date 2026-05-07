package io.horizontalsystems.litecoinkit.mweb.daemon

import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.MwebAccountKeys
import io.horizontalsystems.litecoinkit.mweb.MwebError
import io.horizontalsystems.litecoinkit.mweb.MwebSyncState
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo
import java.io.Closeable
import java.io.File

interface MwebDaemonClient {
    fun start(statusTimeoutMillis: Long = DEFAULT_STATUS_TIMEOUT_MILLIS): MwebDaemonStatus
    fun stop()
    fun status(statusTimeoutMillis: Long = DEFAULT_STATUS_TIMEOUT_MILLIS): MwebDaemonStatus
    /**
     * Returns addresses for the inclusive index range.
     */
    fun addresses(fromIndex: Int, toIndex: Int): List<String>
    fun utxos(fromHeight: Int, onUtxo: (MwebUtxo) -> Unit, onError: (Throwable) -> Unit): Closeable
    fun spent(outputIds: List<String>): List<String>
    fun create(rawTransaction: ByteArray, feeRate: Int, dryRun: Boolean): MwebCreateResult
    fun broadcast(rawTransaction: ByteArray): String

    companion object {
        const val DEFAULT_STATUS_TIMEOUT_MILLIS = 10_000L
    }
}

data class MwebDaemonStatus(
    val syncState: MwebSyncState,
    val nativeVersion: String,
)

data class MwebCreateResult(
    val rawTransaction: ByteArray,
    val outputIds: List<String>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MwebCreateResult) return false

        return rawTransaction.contentEquals(other.rawTransaction) &&
            outputIds == other.outputIds
    }

    override fun hashCode(): Int {
        var result = rawTransaction.contentHashCode()
        result = 31 * result + outputIds.hashCode()
        return result
    }
}

data class MwebDaemonConfig(
    val networkType: LitecoinKit.NetworkType,
    val accountKeys: MwebAccountKeys,
    val peerAddress: String?,
    val dataDir: File,
    val restoreHeight: Int,
)

fun interface MwebDaemonClientFactory {
    fun create(config: MwebDaemonConfig): MwebDaemonClient
}

object MissingMwebDaemonClientFactory : MwebDaemonClientFactory {
    override fun create(config: MwebDaemonConfig): MwebDaemonClient {
        throw MwebError.NativeUnavailable()
    }
}

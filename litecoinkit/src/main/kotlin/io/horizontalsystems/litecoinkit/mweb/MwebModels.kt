package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.bitcoincore.storage.UnspentOutputInfo
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClientFactory
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebdKmpDaemonClientFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Confirmed and unconfirmed MWEB balance in litoshi. */
data class MwebBalance(
    val confirmed: Long,
    val unconfirmed: Long,
)

/**
 * Restore point for MWEB scanning. `Activation` resolves to the network
 * activation height; wallet birthday should be passed as `BlockHeight`.
 */
sealed interface MwebRestorePoint {
    /** Start scanning from the network MWEB activation height. */
    data object Activation : MwebRestorePoint

    /** Start scanning from a wallet birthday or restored checkpoint height. */
    data class BlockHeight(val height: Int) : MwebRestorePoint
}

/** Last daemon sync heights persisted by the MWEB database. */
data class MwebSyncState(
    val blockHeaderHeight: Int,
    val mwebHeaderHeight: Int,
    val mwebUtxosHeight: Int,
) {
    fun isSynced(publicTipHeight: Int, tolerance: Int = 1): Boolean {
        if (
            publicTipHeight <= 0 ||
            blockHeaderHeight <= 0 ||
            mwebHeaderHeight <= 0 ||
            mwebUtxosHeight <= 0
        ) {
            return false
        }

        return publicTipHeight - blockHeaderHeight <= tolerance &&
            publicTipHeight - mwebHeaderHeight <= tolerance &&
            publicTipHeight - mwebUtxosHeight <= tolerance
    }
}

data class MwebDeliveryState(
    val utxoDeliveryHeight: Int,
)

/**
 * Debug-only daemon/storage snapshot. It intentionally exposes only counters and
 * heights, never scan/spend secrets or raw transactions.
 */
data class MwebDebugInfo(
    val state: MwebSyncState,
    val peerAddress: String?,
    val addressPoolSize: Int,
    val unspentUtxoCount: Int,
    val pendingTransactionCount: Int,
    val nativeVersion: String,
)

/** Injects the worker dispatcher for storage/native calls and callback delivery. */
interface MwebDispatcherProvider {
    /** Dispatcher for native daemon calls, Room queries, and file I/O. */
    val io: CoroutineDispatcher

    /** Dispatcher used for listener callbacks. */
    val callback: CoroutineDispatcher
}

/** Default dispatcher provider for MWEB when the host app adapts its own dispatchers. */
class CoroutineMwebDispatcherProvider(
    override val io: CoroutineDispatcher,
    override val callback: CoroutineDispatcher = io,
) : MwebDispatcherProvider

/** Raw account key material passed to mwebd. Do not log, parcel, or put in intents. */
class MwebAccountKeys(
    scanSecret: ByteArray,
    spendSecret: ByteArray,
    spendPublicKey: ByteArray,
) {
    val scanSecret: ByteArray = scanSecret.copyOf()
    val spendSecret: ByteArray = spendSecret.copyOf()
    val spendPublicKey: ByteArray = spendPublicKey.copyOf()
}

/**
 * MWEB daemon and restore configuration for LitecoinKit.
 *
 * MWEB is one seed-derived account per Litecoin wallet and network. It is not
 * duplicated for BIP44/BIP49/BIP84/BIP86 public Litecoin purposes; this
 * invariant is enforced by LitecoinMwebEngineRegistry.
 */
data class MwebConfig(
    /** Dispatchers used by all MWEB blocking work and callbacks. */
    val dispatcherProvider: MwebDispatcherProvider,

    /** Restore point for MWEB UTXO scanning. */
    val restorePoint: MwebRestorePoint = MwebRestorePoint.Activation,

    /**
     * Optional Litecoin P2P peer for mwebd. When null, mwebd uses its own
     * DNS/P2P discovery for NODE_MWEB_LIGHT_CLIENT peers.
     */
    val peerAddress: String? = null,

    /** Factory for the native daemon binding; override in tests only. */
    val daemonClientFactory: MwebDaemonClientFactory = MwebdKmpDaemonClientFactory,
)

/**
 * Native daemon configuration used only for public Litecoin peg-in sends to MWEB.
 *
 * This does not create a wallet MWEB engine, does not scan MWEB UTXOs, and does
 * not persist account keys. It exists so public Litecoin wallets can send to an
 * MWEB address without enabling a local MWEB account. By default it uses
 * Dispatchers.IO; hosts with custom [MwebConfig.dispatcherProvider] should pass
 * the same provider here. Synchronous previews must not be called from the same
 * single-thread dispatcher used as [dispatcherProvider.io].
 */
data class MwebPublicSendConfig(
    /** Dispatchers used by blocking native calls. */
    val dispatcherProvider: MwebDispatcherProvider = CoroutineMwebDispatcherProvider(Dispatchers.IO),

    /**
     * Optional Litecoin P2P peer for mwebd. When null, mwebd uses its own
     * DNS/P2P discovery for NODE_MWEB_LIGHT_CLIENT peers.
     */
    val peerAddress: String? = null,

    /** Factory for the native daemon binding; override in tests only. */
    val daemonClientFactory: MwebDaemonClientFactory = MwebdKmpDaemonClientFactory,
)

/** Fee/selection preview returned before creating and broadcasting a send. */
data class MwebSendInfo(
    /** Public UTXOs selected for peg-in; empty for MWEB-funded sends. */
    val selectedPublicUtxos: List<UnspentOutputInfo>,

    /** MWEB UTXOs selected for peg-out or pure MWEB sends; empty for peg-in. */
    val selectedMwebUtxos: List<MwebUtxo>,

    /** Canonical transaction fee part in litoshi. */
    val normalFee: Long,

    /**
     * MWEB/HogEx fee part in litoshi computed locally from the canonical ltcd
     * `mweb.EstimateFee` formula. Daemon dry-run is not used for this value
     * because it strips outputs for peg-out and pure MWEB sends.
     */
    val mwebFee: Long,

    /** Sum of [normalFee] and [mwebFee]. */
    val totalFee: Long,

    /** Change output value in litoshi, or null when no change is created. */
    val changeValue: Long?,

    /** Change address when known; null when no change output is created. */
    val changeAddress: String?,
)

/**
 * Signed MWEB-aware raw transaction created without broadcasting or mutating
 * local pending/history state.
 */
data class MwebSignedRawTransaction(
    /** Public canonical hash for peg-in, full raw payload hash for MWEB-funded sends. */
    val transactionHash: String,

    /** Canonical public Litecoin transaction hash when known immediately. */
    val canonicalTransactionHash: String?,

    /** Final signed raw transaction bytes. */
    val rawTransaction: ByteArray,

    /** MWEB output identifiers created by mwebd. */
    val outputIds: List<String>,

    /** Total transaction fee in litoshi. */
    val fee: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MwebSignedRawTransaction) return false

        return transactionHash == other.transactionHash &&
            canonicalTransactionHash == other.canonicalTransactionHash &&
            rawTransaction.contentEquals(other.rawTransaction) &&
            outputIds == other.outputIds &&
            fee == other.fee
    }

    override fun hashCode(): Int {
        var result = transactionHash.hashCode()
        result = 31 * result + (canonicalTransactionHash?.hashCode() ?: 0)
        result = 31 * result + rawTransaction.contentHashCode()
        result = 31 * result + outputIds.hashCode()
        result = 31 * result + fee.hashCode()
        return result
    }
}

/** Locally persisted MWEB send awaiting confirmation/spent reconciliation. */
data class MwebPendingTransaction(
    /** Raw transaction bytes; do not expose through UI logs. */
    val rawTransaction: ByteArray,

    /** MWEB output identifiers created by mwebd. */
    val createdOutputIds: List<String>,

    /** Canonical transaction hash for peg-in/peg-out, null for pure MWEB sends. */
    val canonicalTransactionHash: String?,

    /** Local creation timestamp in milliseconds since epoch. */
    val timestamp: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MwebPendingTransaction) return false

        return rawTransaction.contentEquals(other.rawTransaction) &&
            createdOutputIds == other.createdOutputIds &&
            canonicalTransactionHash == other.canonicalTransactionHash &&
            timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = rawTransaction.contentHashCode()
        result = 31 * result + createdOutputIds.hashCode()
        result = 31 * result + (canonicalTransactionHash?.hashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/** User-visible MWEB transaction history item. */
data class MwebTransaction(
    val uid: String,
    val type: MwebTransactionType,
    val kind: MwebTransactionKind,
    val amount: Long,
    val fee: Long?,
    val address: String?,
    val canonicalTransactionHash: String?,
    val outputIds: List<String>,
    val inputOutputIds: List<String>,
    val height: Int?,
    val timestamp: Long,
    val pending: Boolean,
)

enum class MwebTransactionType {
    Incoming,
    Outgoing,
}

enum class MwebTransactionKind {
    Incoming,
    PublicToMweb,
    MwebToPublic,
    MwebToMweb,
}

/**
 * MWEB output known to the wallet. `address` can be empty for daemon records
 * that do not map to a locally cached receive/change address.
 */
data class MwebUtxo(
    /** mwebd output identifier in hex. */
    val outputId: String,

    /** Cached address when mwebd output maps to a known local index; can be empty for change. */
    val address: String,

    /** MWEB address index. Index 0 is change, indices >= 1 are receive addresses. */
    val addressIndex: Int,

    /** Output value in litoshi. */
    val value: Long,

    /** Block height, or 0 while unconfirmed. */
    val height: Int,

    /** Block timestamp reported by mwebd, or 0 while unconfirmed. */
    val blockTime: Long,

    /** True after mwebd reports the output as spent or the local send consumes it. */
    val spent: Boolean,
) {
    val confirmed: Boolean
        get() = height > 0

    fun confirmations(tipHeight: Int): Int {
        if (!confirmed || tipHeight < height) return 0
        return tipHeight - height + 1
    }
}

/**
 * MWEB-aware send request.
 *
 * Pure public-to-public sends stay in LitecoinKit and are not representable here,
 * so every variant has at least one MWEB side.
 */
sealed interface MwebSendRequest {
    /** Destination address: MWEB for peg-in/private sends, public Litecoin for peg-out. */
    val address: String

    /** Recipient value in litoshi, excluding fees. */
    val value: Long

    /** Fee rate in litoshi per vbyte. */
    val feeRate: Int

    /** Public Litecoin UTXOs fund an MWEB output. */
    data class PublicToMweb(
        override val address: String,
        override val value: Long,
        override val feeRate: Int,
    ) : MwebSendRequest

    /** MWEB UTXOs fund a canonical Litecoin output. Requires six MWEB confirmations. */
    data class MwebToPublic(
        override val address: String,
        override val value: Long,
        override val feeRate: Int,
    ) : MwebSendRequest

    /** MWEB UTXOs fund another MWEB output. */
    data class MwebToMweb(
        override val address: String,
        override val value: Long,
        override val feeRate: Int,
    ) : MwebSendRequest
}

/**
 * Broadcast result. `canonicalTransactionHash` is present for peg-in transactions.
 * MWEB-originated sends receive a public canonical hash only after the MWEB block is known.
 */
data class MwebSendResult(
    /** Canonical transaction hash for public Litecoin explorers, when known. */
    val canonicalTransactionHash: String?,

    /** Broadcast raw transaction bytes returned after public input signing. */
    val rawTransaction: ByteArray,

    /** MWEB output identifiers created by mwebd. */
    val outputIds: List<String>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MwebSendResult) return false

        return canonicalTransactionHash == other.canonicalTransactionHash &&
            rawTransaction.contentEquals(other.rawTransaction) &&
            outputIds == other.outputIds
    }

    override fun hashCode(): Int {
        var result = canonicalTransactionHash?.hashCode() ?: 0
        result = 31 * result + rawTransaction.contentHashCode()
        result = 31 * result + outputIds.hashCode()
        return result
    }
}

/** Typed MWEB errors so the wallet can degrade to public-only Litecoin UX. */
sealed class MwebError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Native binding is missing, failed to load, or daemon status timed out before startup. */
    class NativeUnavailable(cause: Throwable? = null) :
        MwebError("MWEB native library is unavailable", cause)

    /** Native daemon call failed after the binding was available. */
    class DaemonCrashed(cause: Throwable? = null) :
        MwebError("MWEB daemon stopped unexpectedly", cause)

    /** MWEB state is not ready or a sync/status operation failed. */
    class SyncFailure(cause: Throwable? = null) :
        MwebError("MWEB sync failed", cause)

    /** Peg-out attempted with MWEB outputs below the six-confirmation network rule. */
    class InsufficientMwebConfirmations :
        MwebError("MWEB outputs require more confirmations")

    /** Neither public nor MWEB selected funds can cover value plus fees. */
    class InsufficientFunds :
        MwebError("Insufficient funds")
}

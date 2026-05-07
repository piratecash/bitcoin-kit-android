package io.horizontalsystems.litecoinkit

import io.horizontalsystems.bitcoincore.models.BitcoinSendInfo
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.litecoinkit.mweb.MwebBalance
import io.horizontalsystems.litecoinkit.mweb.MwebDebugInfo
import io.horizontalsystems.litecoinkit.mweb.MwebPendingTransaction
import io.horizontalsystems.litecoinkit.mweb.MwebSendInfo
import io.horizontalsystems.litecoinkit.mweb.MwebSendResult
import io.horizontalsystems.litecoinkit.mweb.MwebSyncState
import io.horizontalsystems.litecoinkit.mweb.MwebTransaction
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo

/** Selects canonical Litecoin or MWEB receive address generation. */
enum class LitecoinReceiveAddressType {
    /** Canonical Litecoin address from the public Litecoin account. */
    Public,

    /** MWEB address from the wallet's MWEB account. */
    Mweb,
}

/**
 * Selects which balance pool funds a send. `Auto` currently uses public UTXOs
 * for MWEB destinations and canonical Litecoin flow for public destinations.
 */
enum class LitecoinSendSource {
    /** Uses public UTXOs for MWEB destinations and canonical flow for public destinations. */
    Auto,

    /** Forces public UTXOs as the source; public-to-public remains canonical Litecoin. */
    Public,

    /** Forces MWEB UTXOs as the source for peg-out or pure MWEB sends. */
    Mweb,
}

/**
 * Combined public/MWEB balance exposed by a single LitecoinKit instance.
 *
 * `mweb` is null when LitecoinKit was created without [io.horizontalsystems.litecoinkit.mweb.MwebConfig].
 */
data class LitecoinBalance(
    val publicSpendable: Long,
    val publicUnspendable: Long,
    val mweb: MwebBalance?,
) {
    val totalSpendable: Long
        get() = publicSpendable + (mweb?.confirmed ?: 0) + (mweb?.unconfirmed ?: 0)
}

/** Fee/selection preview for either canonical Litecoin or MWEB-aware sends. */
sealed interface LitecoinSendInfo {
    /** Canonical Litecoin send preview. */
    data class Public(val sendInfo: BitcoinSendInfo) : LitecoinSendInfo

    /** Peg-in, peg-out, or pure MWEB send preview. */
    data class Mweb(val sendInfo: MwebSendInfo) : LitecoinSendInfo
}

/** Send result for either canonical Litecoin or an MWEB-aware transaction. */
sealed interface LitecoinSendResult {
    /** Canonical Litecoin transaction created by bitcoincore. */
    data class Public(val transaction: FullTransaction) : LitecoinSendResult

    /** Peg-in, peg-out, or pure MWEB transaction result from mwebd. */
    data class Mweb(val transaction: MwebSendResult) : LitecoinSendResult
}

/**
 * Current MWEB runtime state.
 *
 * LitecoinKit returns null when MWEB is not enabled. Reading this state can touch
 * MWEB storage through blocking APIs, so do not read it from Android main thread.
 */
data class LitecoinMwebState(
    val balance: MwebBalance,
    val syncState: MwebSyncState,
    val debugInfo: MwebDebugInfo,
    val utxos: List<MwebUtxo>,
    val pendingTransactions: List<MwebPendingTransaction>,
    val transactions: List<MwebTransaction>,
)

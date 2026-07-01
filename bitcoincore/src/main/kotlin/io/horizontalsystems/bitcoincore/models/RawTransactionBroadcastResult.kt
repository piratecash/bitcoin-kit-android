package io.horizontalsystems.bitcoincore.models

import io.horizontalsystems.bitcoincore.storage.FullTransaction

data class RawTransactionBroadcastResult(
    val transaction: FullTransaction,
    val status: RawTransactionBroadcastStatus,
)

enum class RawTransactionBroadcastStatus {
    Submitted, Queued, AlreadyKnown
}

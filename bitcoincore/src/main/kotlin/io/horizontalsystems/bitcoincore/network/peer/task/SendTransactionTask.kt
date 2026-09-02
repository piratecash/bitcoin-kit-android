package io.horizontalsystems.bitcoincore.network.peer.task

import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.models.InventoryItem
import io.horizontalsystems.bitcoincore.network.messages.GetDataMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.InvMessage
import io.horizontalsystems.bitcoincore.network.messages.TransactionMessage
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import java.util.concurrent.TimeUnit

// storage/external are nullable/default so existing tests that construct this task directly for
// unrelated protocol behavior keep compiling; production always supplies both (see TransactionSender).
class SendTransactionTask(
    val transaction: FullTransaction,
    private val storage: IStorage? = null,
    private val external: Boolean = false,
) : PeerTask() {

    enum class CompletionReason {
        REQUESTED_BY_PEER,
        TIMEOUT,
    }

    var completionReason: CompletionReason? = null

    init {
        allowedIdleTime = TimeUnit.SECONDS.toMillis(30)
    }

    override val state: String
        get() = "transaction: ${transaction.header.hash.toReversedHex()}"

    override fun start() {
        completionReason = null
        requester?.send(InvMessage(InventoryItem.MSG_TX, transaction.header.hash))
        resetTimer()
    }

    override fun handleMessage(message: IMessage): Boolean {
        val transactionRequested =
                message is GetDataMessage &&
                message.inventory.any { it.type == InventoryItem.MSG_TX && it.hash.contentEquals(transaction.header.hash) }

        if (!transactionRequested) {
            return false
        }

        if (isStillBroadcastable()) {
            completionReason = CompletionReason.REQUESTED_BY_PEER
            requester?.send(TransactionMessage(transaction, 0))
        } else {
            // Own transaction was deleted from storage (expired without ever broadcasting) or is
            // already mined: let the peer's request go unanswered instead of sending bytes for a
            // transaction we no longer own.
            completionReason = CompletionReason.TIMEOUT
        }
        listener?.onTaskCompleted(this)

        return true
    }

    override fun handleTimeout() {
        completionReason = CompletionReason.TIMEOUT
        listener?.onTaskCompleted(this)
    }

    // Plain existence/pending re-check: every peer's getdata for a still-pending transaction is
    // served (no ownership gate), only a deleted (expired) or mined transaction is refused. No lock
    // is needed here - PendingTransactionReconciler's NEW-expiry delete is blocked structurally by
    // the SentTransaction record TransactionSender writes before this task is even started.
    private fun isStillBroadcastable(): Boolean {
        val trackedStorage = storage ?: return true
        if (external) {
            return true
        }
        val stored = trackedStorage.getTransaction(transaction.header.hash) ?: return false
        return stored.blockHash == null
    }

}

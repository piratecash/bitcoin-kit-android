package io.horizontalsystems.piratecashkit.tasks

import io.horizontalsystems.bitcoincore.models.InventoryItem
import io.horizontalsystems.bitcoincore.network.messages.GetDataMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.TransactionMessage
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.bitcoincore.storage.FullTransaction

/**
 * Task for requesting transactions that have validated ISLocks but haven't been received yet.
 * This is separate from RequestTransactionsTask to allow InstantSend handler to process
 * only transactions it requested, while MempoolTransactions handles regular transaction requests.
 */
class RequestInstantTransactionsTask(txHashes: List<ByteArray>) : PeerTask() {

    val hashes = txHashes.toMutableList()
    var transactions = mutableListOf<FullTransaction>()

    override val state: String
        get() = "requestedHashes: ${hashes.size}; receivedTransactions: ${transactions.size}"

    override fun start() {
        val items = hashes.map { hash ->
            InventoryItem(InventoryItem.MSG_TX, hash)
        }

        requester?.send(GetDataMessage(items))
        resetTimer()
    }

    override fun handleMessage(message: IMessage): Boolean = when (message) {
        is TransactionMessage -> handleTransaction(message)
        else -> false
    }

    private fun handleTransaction(message: TransactionMessage): Boolean {
        val transaction = message.transaction
        val hash = hashes.firstOrNull { it.contentEquals(transaction.header.hash) } ?: return false

        hashes.remove(hash)
        transactions.add(transaction)

        if (hashes.isEmpty()) {
            listener?.onTaskCompleted(this)
        }

        return true
    }
}

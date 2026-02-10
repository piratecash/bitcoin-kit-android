package io.horizontalsystems.bitcoincore.network.peer

import io.horizontalsystems.bitcoincore.models.InventoryItem
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.bitcoincore.network.peer.task.RequestTransactionsTask
import io.horizontalsystems.bitcoincore.transactions.TransactionSender
import io.horizontalsystems.bitcoincore.transactions.TransactionSyncer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class MempoolTransactions(
        private val transactionSyncer: TransactionSyncer,
        private val transactionSender: TransactionSender?
) : IPeerTaskHandler, IInventoryItemsHandler, PeerGroup.Listener {

    private val requestedTransactions = ConcurrentHashMap<String, CopyOnWriteArrayList<ByteArray>>()

    override fun handleCompletedTask(peer: Peer, task: PeerTask): Boolean {
        if (task.owner != null && task.owner !== this) return false

        return when (task) {
            is RequestTransactionsTask -> {
                transactionSyncer.handleRelayed(task.transactions)
                removeFromRequestedTransactions(peer.host, task.transactions.map { it.header.hash })
                transactionSender?.transactionsRelayed(task.transactions)
                true
            }
            else -> false
        }
    }

    override fun handleInventoryItems(peer: Peer, inventoryItems: List<InventoryItem>) {
        val transactionHashes = mutableListOf<ByteArray>()

        inventoryItems.forEach { item ->
            if (item.type == InventoryItem.MSG_TX
                    && !isTransactionRequested(item.hash)
                    && transactionSyncer.shouldRequestTransaction(item.hash)) {
                transactionHashes.add(item.hash)
            }
        }

        if (transactionHashes.isNotEmpty()) {
            val task = RequestTransactionsTask(transactionHashes)
            task.owner = this
            peer.addTask(task)

            addToRequestedTransactions(peer.host, transactionHashes)
        }
    }

    override fun onPeerDisconnect(peer: Peer, e: Exception?) {
        requestedTransactions.remove(peer.host)
    }

    private fun addToRequestedTransactions(peerHost: String, transactionHashes: List<ByteArray>) {
        requestedTransactions.getOrPut(peerHost) { CopyOnWriteArrayList() }.addAll(transactionHashes)
    }

    private fun removeFromRequestedTransactions(peerHost: String, transactionHashes: List<ByteArray>) {
        val list = requestedTransactions[peerHost] ?: return
        list.removeIf { element -> transactionHashes.any { it.contentEquals(element) } }
    }

    private fun isTransactionRequested(hash: ByteArray): Boolean {
        return requestedTransactions.any { (_, inventories) ->
            inventories.any {
                it.contentEquals(hash)
            }
        }
    }
}

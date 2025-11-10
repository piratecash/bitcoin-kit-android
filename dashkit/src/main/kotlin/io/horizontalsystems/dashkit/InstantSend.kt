package io.horizontalsystems.dashkit

import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.models.InventoryItem
import io.horizontalsystems.bitcoincore.network.peer.IInventoryItemsHandler
import io.horizontalsystems.bitcoincore.network.peer.IPeerTaskHandler
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.transactions.TransactionSyncer
import io.horizontalsystems.dashkit.instantsend.instantsendlock.InstantSendLockHandler
import io.horizontalsystems.dashkit.instantsend.transactionlockvote.TransactionLockVoteHandler
import io.horizontalsystems.dashkit.messages.ISLockMessage
import io.horizontalsystems.dashkit.messages.TransactionLockVoteMessage
import io.horizontalsystems.dashkit.tasks.RequestInstantSendLocksTask
import io.horizontalsystems.dashkit.tasks.RequestInstantTransactionsTask
import io.horizontalsystems.dashkit.tasks.RequestTransactionLockRequestsTask
import io.horizontalsystems.dashkit.tasks.RequestTransactionLockVotesTask
import timber.log.Timber
import java.util.concurrent.Executors

class InstantSend(
        private val transactionSyncer: TransactionSyncer,
        private val transactionLockVoteHandler: TransactionLockVoteHandler,
        private val instantSendLockHandler: InstantSendLockHandler
) : IInventoryItemsHandler, IPeerTaskHandler {

    private val dispatchQueue = Executors.newSingleThreadExecutor()

    fun handle(insertedTxHash: ByteArray) {
        instantSendLockHandler.handle(insertedTxHash)
    }

    override fun handleInventoryItems(peer: Peer, inventoryItems: List<InventoryItem>) {
        val transactionLockRequests = mutableListOf<ByteArray>()
        val transactionLockVotes = mutableListOf<ByteArray>()
        val isLocks = mutableListOf<InventoryItem>()

        inventoryItems.forEach { item ->
            when (item.type) {
                InventoryType.MSG_TXLOCK_REQUEST -> {
                    transactionLockRequests.add(item.hash)
                }
                InventoryType.MSG_TXLOCK_VOTE -> {
                    transactionLockVotes.add(item.hash)
                }
                InventoryType.MSG_ISLOCK, InventoryType.MSG_ISDLOCK -> {
                    isLocks.add(item)
                }
            }
        }

        if (transactionLockRequests.isNotEmpty()) {
            peer.addTask(RequestTransactionLockRequestsTask(transactionLockRequests))
        }

        if (transactionLockVotes.isNotEmpty()) {
            peer.addTask(RequestTransactionLockVotesTask(transactionLockVotes))
        }

        if (isLocks.isNotEmpty()) {
            peer.addTask(RequestInstantSendLocksTask(isLocks))
        }

    }

    override fun handleCompletedTask(peer: Peer, task: PeerTask): Boolean {
        return when (task) {
            is RequestTransactionLockRequestsTask -> {
                dispatchQueue.execute {
                    handleTransactions(task.transactions)
                }
                true
            }
            is RequestTransactionLockVotesTask -> {
                dispatchQueue.execute {
                    handleTransactionLockVotes(task.transactionLockVotes)
                }
                true
            }
            is RequestInstantSendLocksTask -> {
                dispatchQueue.execute {
                    handleInstantSendLocks(peer, task.isLocks)
                }
                true
            }
            is RequestInstantTransactionsTask -> {
                dispatchQueue.execute {
                    handleInstantTransactions(task.transactions)
                }
                true
            }
            else -> false
        }
    }

    private fun handleTransactions(transactions: List<FullTransaction>) {
        transactionSyncer.handleRelayed(transactions)

        for (transaction in transactions) {
            transactionLockVoteHandler.handle(transaction)
        }
    }

    private fun handleTransactionLockVotes(transactionLockVotes: List<TransactionLockVoteMessage>) {
        for (vote in transactionLockVotes) {
            transactionLockVoteHandler.handle(vote)
        }
    }

    private fun handleInstantSendLocks(peer: Peer, isLocks: List<ISLockMessage>) {
        for (isLock in isLocks) {
            instantSendLockHandler.handle(peer, isLock)
        }
    }

    private fun handleInstantTransactions(transactions: List<FullTransaction>) {
        // Process transactions that were proactively requested for InstantSend
        if (transactions.isNotEmpty()) {
            val txHashes = transactions.joinToString(", ") { it.header.hash.toReversedHex() }
            Timber.tag("DASH").d("Received ${transactions.size} proactively requested InstantSend transaction(s): $txHashes")
        }
        transactionSyncer.handleRelayed(transactions)
    }

    companion object {
        const val requiredVoteCount = 6
    }

}

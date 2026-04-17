package io.horizontalsystems.bitcoincore.transactions

import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairApi
import io.horizontalsystems.bitcoincore.core.IInitialDownload
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.models.SentTransaction
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.network.messages.RejectMessage
import io.horizontalsystems.bitcoincore.network.peer.IPeerTaskHandler
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerGroup
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.bitcoincore.network.peer.task.SendTransactionTask
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

class TransactionSender(
    private val transactionSyncer: TransactionSyncer,
    private val peerManager: PeerManager,
    private val initialBlockDownload: IInitialDownload,
    private val storage: IStorage,
    private val timer: TransactionSendTimer,
    private val transactionSerializer: BaseTransactionSerializer,
    private val sendType: BitcoinCore.SendType,
    private val maxRetriesCount: Int = 10,
    private val retriesPeriod: Int = 60,
    private val allowBroadcastFromUnsyncedPeers: Boolean,
    private val minConnectedPeerSize: Int = DEFAULT_MIN_CONNECTED_PEER_SIZE,
    private val logTag: String = "BitcoinCore",
) : IPeerTaskHandler, TransactionSendTimer.Listener, PeerGroup.Listener {

    private data class BroadcastDiagnostics(
        val requestedByPeer: Boolean = false,
        val rejectedByPeer: Boolean = false,
        val lastRejectDescription: String? = null,
    )

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val broadcastDiagnostics = ConcurrentHashMap<String, BroadcastDiagnostics>()

    fun sendPendingTransactions() {
        try {
            val transactions = transactionSyncer.getNewTransactions()
            if (transactions.isEmpty()) {
                timer.stop()
                return
            }

            val transactionsToSend = getTransactionsToSend(transactions)
            if (transactionsToSend.isNotEmpty()) {
                send(transactionsToSend)
            }

        } catch (e: PeerGroup.Error) {
//            logger.warning("Handling pending transactions failed with: ${e.message}")
        }
    }

    fun canSendTransaction() {
        if (getPeersToSend().isEmpty()) {
            val connectedPeers = peerManager.peersCount
            val syncedPeers = initialBlockDownload.syncedPeers.size
            val readyPeers = peerManager.readyPears().size
            throw PeerGroup.Error(
                "Peers not synced: connected=$connectedPeers, synced=$syncedPeers, ready=$readyPeers, minRequired=$minConnectedPeerSize"
            )
        }
    }

    fun transactionsRelayed(transactions: List<FullTransaction>) {
        transactions.forEach { transaction ->
            clearDiagnostics(transaction.header.hash)
            storage.getSentTransaction(transaction.header.hash)?.let { sentTransaction ->
                storage.deleteSentTransaction(sentTransaction)
            }
            Timber.tag(logTag).i("Transaction ${transaction.header.hash.toReversedHex()} observed from peer mempool and marked relayed.")
        }
    }

    private fun getTransactionsToSend(transactions: List<FullTransaction>): List<FullTransaction> {
        return transactions.filter { transaction ->
            storage.getSentTransaction(transaction.header.hash)?.let { sentTransaction ->
                sentTransaction.retriesCount < maxRetriesCount && sentTransaction.lastSendTime < (System.currentTimeMillis() - retriesPeriod * 1000)
            } ?: true
        }
    }

    private fun getPeersToSend(): List<Peer> {
        if (peerManager.peersCount < minConnectedPeerSize) {
            return emptyList()
        }

        val freeSyncedPeer = initialBlockDownload.syncedPeers
            .minByOrNull { it.ready }

        if (!allowBroadcastFromUnsyncedPeers && freeSyncedPeer == null) {
            return emptyList()
        }

        val syncedPeerHosts = initialBlockDownload.syncedPeers.map { it.host }.toSet()
        val readyPeers = peerManager.readyPears()
            .filter { it != freeSyncedPeer }
            .sortedBy { it.host in syncedPeerHosts } // not synced first

        if (readyPeers.size == 1) {
            return readyPeers
        }

        return readyPeers.take(readyPeers.size / 2)
    }

    private fun send(transactions: List<FullTransaction>) {
        when (sendType) {
            BitcoinCore.SendType.P2P -> {
                sendViaP2P(transactions)
            }

            is BitcoinCore.SendType.API -> {
                sendViaAPI(transactions, sendType.blockchairApi) {
                    sendViaP2P(transactions)
                }
            }
        }
    }

    private fun sendViaAPI(transactions: List<FullTransaction>, blockchairApi: BlockchairApi, fallback: () -> Boolean) = coroutineScope.launch {
        transactions.forEach { transaction ->
            val txHash = transaction.header.hash.toReversedHex()
            try {
                val hex = transactionSerializer.serialize(transaction).toHexString()
                blockchairApi.broadcastTransaction(hex)

                Timber.tag(logTag).i("Transaction $txHash accepted by API broadcast.")
                transactionSyncer.handleRelayed(listOf(transaction))
            } catch (error: Throwable) {
                Timber.tag(logTag).w(error, "API broadcast failed for tx=$txHash. Falling back to peer-to-peer broadcast.")
                val sent = fallback()
                if (!sent) {
                    Timber.tag(logTag).w("API fallback could not broadcast tx=$txHash because no eligible peers were available.")
                    transactionSyncer.handleInvalid(transaction)
                }
            }
        }
    }

    private fun sendViaP2P(transactions: List<FullTransaction>): Boolean {
        val peers = getPeersToSend()
        if (peers.isEmpty()) {
            Timber.tag(logTag).d(
                "Skipping peer-to-peer broadcast. connected=${peerManager.peersCount}, " +
                    "synced=${initialBlockDownload.syncedPeers.size}, ready=${peerManager.readyPears().size}"
            )
            return false
        }

        timer.startIfNotRunning()

        transactions.forEach { transaction ->
            transactionSendStart(transaction, peers)

            peers.forEach { peer ->
                val task = SendTransactionTask(transaction)
                task.owner = this@TransactionSender
                peer.addTask(task)
            }
        }
        return true
    }

    private fun transactionSendStart(transaction: FullTransaction, peers: List<Peer>) {
        val txHash = transaction.header.hash.toReversedHex()
        val sentTransaction = storage.getSentTransaction(transaction.header.hash)
        ensureDiagnostics(transaction.header.hash)

        if (sentTransaction == null) {
            storage.addSentTransaction(SentTransaction(transaction.header.hash))
        } else {
            sentTransaction.lastSendTime = System.currentTimeMillis()
            sentTransaction.sendSuccess = false
            storage.updateSentTransaction(sentTransaction)
        }

        Timber.tag(logTag).d(
            "Broadcast attempt for tx=$txHash sendType=P2P retry=${(sentTransaction?.retriesCount ?: 0) + 1} peers=${peers.joinToString { it.host }}"
        )
    }

    @Synchronized
    private fun transactionSendAttemptCompleted(peer: Peer, task: SendTransactionTask) {
        val txHash = task.transaction.header.hash.toReversedHex()
        when (task.completionReason) {
            SendTransactionTask.CompletionReason.REQUESTED_BY_PEER -> {
                markRequestedByPeer(task.transaction.header.hash)
                Timber.tag(logTag).i("Peer ${peer.host} requested tx=$txHash.")
            }

            SendTransactionTask.CompletionReason.TIMEOUT, null -> {
                Timber.tag(logTag).d("Peer ${peer.host} did not request tx=$txHash before timeout.")
            }
        }

        val transaction = task.transaction
        val sentTransaction = storage.getSentTransaction(transaction.header.hash)

        if (sentTransaction == null || sentTransaction.sendSuccess) {
            return
        }

        sentTransaction.retriesCount++
        sentTransaction.sendSuccess = true

        if (sentTransaction.retriesCount >= maxRetriesCount) {
            val state = clearDiagnostics(transaction.header.hash)
            Timber.tag(logTag).w(
                "Broadcast attempts exhausted for tx=$txHash retries=${sentTransaction.retriesCount} " +
                    "requestedByPeer=${state?.requestedByPeer == true} rejectedByPeer=${state?.rejectedByPeer == true} " +
                    "lastReject=${state?.lastRejectDescription ?: "<none>"}"
            )
            transactionSyncer.handleInvalid(transaction)
            storage.deleteSentTransaction(sentTransaction)
        } else {
            storage.updateSentTransaction(sentTransaction)
        }
    }

    // IPeerTaskHandler

    override fun handleCompletedTask(peer: Peer, task: PeerTask): Boolean {
        if (task.owner != null && task.owner !== this) return false

        return when (task) {
            is SendTransactionTask -> {
                transactionSendAttemptCompleted(peer, task)
                true
            }

            else -> false
        }
    }

    // PeerGroup.Listener

    override fun onPeerReject(peer: Peer, rejectMessage: RejectMessage) {
        if (rejectMessage.responseToMessage != "tx") {
            return
        }

        val rejectedHash = rejectMessage.rejectedHash ?: return
        if (!isTrackedOutgoingTransaction(rejectedHash)) {
            return
        }

        markRejectedByPeer(
            hash = rejectedHash,
            description = "${rejectMessage.rejectCodeName}: ${rejectMessage.reason.ifBlank { "<empty>" }}",
        )

        Timber.tag(logTag).w(
            "Peer ${peer.host} rejected tx=${rejectedHash.toReversedHex()} code=${rejectMessage.rejectCodeName} reason=${rejectMessage.reason.ifBlank { "<empty>" }}"
        )
    }

    // TransactionSendTimer.Listener

    override fun onTimePassed() {
        sendPendingTransactions()
    }

    private fun ensureDiagnostics(hash: ByteArray) {
        val key = hash.toReversedHex()
        val current = broadcastDiagnostics[key]
        if (current != null) {
            return
        }

        val diagnostics = BroadcastDiagnostics()
        broadcastDiagnostics.putIfAbsent(key, diagnostics)
    }

    private fun markRequestedByPeer(hash: ByteArray) {
        val key = hash.toReversedHex()
        broadcastDiagnostics.compute(key) { _, current ->
            (current ?: BroadcastDiagnostics()).copy(requestedByPeer = true)
        }
    }

    private fun markRejectedByPeer(hash: ByteArray, description: String) {
        val key = hash.toReversedHex()
        broadcastDiagnostics.compute(key) { _, current ->
            (current ?: BroadcastDiagnostics()).copy(
                rejectedByPeer = true,
                lastRejectDescription = description,
            )
        }
    }

    private fun clearDiagnostics(hash: ByteArray): BroadcastDiagnostics? {
        return broadcastDiagnostics.remove(hash.toReversedHex())
    }

    private fun isTrackedOutgoingTransaction(hash: ByteArray): Boolean {
        if (storage.getSentTransaction(hash) != null) {
            return true
        }

        val transaction = storage.getTransaction(hash) ?: return false
        return transaction.isOutgoing && transaction.status == Transaction.Status.NEW
    }

    companion object {
        const val DEFAULT_MIN_CONNECTED_PEER_SIZE = 2
    }
}

package io.horizontalsystems.bitcoincore.transactions

import co.touchlab.kermit.Logger
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairApi
import io.horizontalsystems.bitcoincore.core.IInitialDownload
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.models.RawTransactionBroadcastStatus
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IPeerTaskHandler, TransactionSendTimer.Listener, PeerGroup.Listener {
    private val log = Logger.withTag(logTag)

    private data class BroadcastDiagnostics(
        val requestedByPeer: Boolean = false,
        val rejectedByPeer: Boolean = false,
        val lastRejectDescription: String? = null,
    )

    private data class BroadcastTransaction(
        val transaction: FullTransaction,
        val rawTransactionHex: String? = null,
        val external: Boolean = false,
    )

    private val coroutineScope = CoroutineScope(coroutineDispatcher)
    private val broadcastDiagnostics = ConcurrentHashMap<String, BroadcastDiagnostics>()

    @Volatile
    private var networkPaused = false

    /**
     * Stops the automatic retry loop. An explicit [broadcastRawTransaction] stays allowed:
     * only the kit's own background broadcasts are suspended.
     */
    fun pauseNetwork() {
        networkPaused = true
        timer.stop()
        coroutineScope.coroutineContext.cancelChildren()
    }

    fun resumeNetwork() {
        if (!networkPaused) return

        networkPaused = false
        // pauseNetwork() stopped the retry timer, and the peers-synced callback that would
        // normally restart it may never come. The first tick stops itself if nothing is queued.
        timer.startIfNotRunning()
    }

    fun sendPendingTransactions() {
        if (networkPaused) return

        try {
            val transactions = pendingBroadcastTransactions()
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

    suspend fun broadcastRawTransaction(
        transaction: FullTransaction,
        rawTransactionHex: String
    ): RawTransactionBroadcastStatus = withContext(Dispatchers.IO) {
        val broadcastTransaction = BroadcastTransaction(
            transaction = transaction,
            rawTransactionHex = rawTransactionHex,
            external = true,
        )

        when (sendType) {
            BitcoinCore.SendType.P2P -> {
                if (!sendViaP2P(listOf(broadcastTransaction))) {
                    // No peer attempted the transaction, so this must not consume the P2P retry budget.
                    recordBroadcastAttempt(broadcastTransaction)
                    queueBroadcastRetry(broadcastTransaction)
                    RawTransactionBroadcastStatus.Queued
                } else {
                    RawTransactionBroadcastStatus.Submitted
                }
            }

            is BitcoinCore.SendType.API -> {
                try {
                    queueExternalBroadcast(broadcastTransaction)
                    return@withContext broadcastViaAPI(broadcastTransaction, sendType.blockchairApi) {
                        sendViaP2P(listOf(broadcastTransaction))
                    }
                } catch (cancellation: CancellationException) {
                    storage.getSentTransaction(transaction.header.hash)?.let { queuedTransaction ->
                        storage.deleteSentTransaction(queuedTransaction)
                    }
                    throw cancellation
                }
            }
        }
    }

    fun transactionsRelayed(transactions: List<FullTransaction>) {
        transactions.forEach { transaction ->
            clearDiagnostics(transaction.header.hash)
            storage.getSentTransaction(transaction.header.hash)?.let { sentTransaction ->
                storage.deleteSentTransaction(sentTransaction)
            }
            log.i { "Transaction ${transaction.header.hash.toReversedHex()} observed from peer mempool and marked relayed." }
        }
    }

    private fun getTransactionsToSend(transactions: List<BroadcastTransaction>): List<BroadcastTransaction> {
        return transactions.filter { broadcastTransaction ->
            storage.getSentTransaction(broadcastTransaction.transaction.header.hash)?.let { sentTransaction ->
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

    private fun send(transactions: List<BroadcastTransaction>) {
        // The retry callback gets here after storage reads, so pauseNetwork() can land in between;
        // sendViaP2P() would restart the timer the pause had just stopped.
        if (networkPaused) return

        when (sendType) {
            BitcoinCore.SendType.P2P -> {
                sendViaP2P(transactions)
            }

            is BitcoinCore.SendType.API -> {
                sendViaAPI(transactions, sendType.blockchairApi)
            }
        }
    }

    private fun sendViaAPI(transactions: List<BroadcastTransaction>, blockchairApi: BlockchairApi) = coroutineScope.launch {
        transactions.forEach { transaction ->
            // The API call blocks uninterruptibly, so cancellation can only land between steps:
            // recheck before starting another broadcast and before falling back to peers.
            if (networkPaused) return@launch

            broadcastViaAPI(transaction, blockchairApi) {
                !networkPaused && sendViaP2P(listOf(transaction))
            }
        }
    }

    private suspend fun broadcastViaAPI(
        transaction: BroadcastTransaction,
        blockchairApi: BlockchairApi,
        fallback: () -> Boolean
    ): RawTransactionBroadcastStatus {
        val txHash = transaction.transaction.header.hash.toReversedHex()

        if (!transaction.external && recordBroadcastAttemptIfPending(transaction) == null) {
            // The pending check and the SentTransaction insert happened atomically in storage, so a
            // concurrent NEW-expiry delete (PendingTransactionReconciler.deleteNewExpiredTransactions)
            // and this record can never both "win": if the delete already committed, no SentTransaction
            // was written here and the transaction's bytes must not be sent. External transactions
            // already have a SentTransaction from queueExternalBroadcast and are not expiry-deleted.
            log.d { "Skipping API broadcast for tx=$txHash: transaction no longer pending." }
            return RawTransactionBroadcastStatus.AlreadyKnown
        }

        try {
            blockchairApi.broadcastTransaction(rawHex(transaction))

            log.i { "Transaction $txHash accepted by API broadcast." }
            markBroadcastAccepted(transaction)
            return RawTransactionBroadcastStatus.Submitted
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            log.w(error) { "API broadcast failed for tx=$txHash. Falling back to peer-to-peer broadcast." }
            val sent = fallback()
            if (!sent) {
                queueBroadcastRetry(transaction)
                return RawTransactionBroadcastStatus.Queued
            }
            return RawTransactionBroadcastStatus.Submitted
        }
    }

    private fun sendViaP2P(transactions: List<BroadcastTransaction>): Boolean {
        val peers = getPeersToSend()
        if (peers.isEmpty()) {
            log.d {
                "Skipping peer-to-peer broadcast. connected=${peerManager.peersCount}, " +
                "synced=${initialBlockDownload.syncedPeers.size}, ready=${peerManager.readyPears().size}"
            }
            return false
        }

        timer.startIfNotRunning()

        transactions.forEach { transaction ->
            if (!transactionSendStart(transaction, peers)) {
                return@forEach
            }

            peers.forEach { peer ->
                val task = SendTransactionTask(transaction.transaction, storage, transaction.external)
                task.owner = this@TransactionSender
                peer.addTask(task)
            }
        }
        return true
    }

    private fun pendingBroadcastTransactions(): List<BroadcastTransaction> {
        val ownTransactions = transactionSyncer.getNewTransactions().map { transaction ->
            BroadcastTransaction(transaction = transaction)
        }

        val externalTransactions = storage.getExternalSentTransactions().mapNotNull { sentTransaction ->
            val rawTransactionHex = sentTransaction.rawTransactionHex
            if (rawTransactionHex == null) {
                storage.deleteSentTransaction(sentTransaction)
                return@mapNotNull null
            }

            try {
                BroadcastTransaction(
                    transaction = transactionSerializer.deserialize(BitcoinInputMarkable(rawTransactionHex.hexToByteArray())),
                    rawTransactionHex = rawTransactionHex,
                    external = true,
                )
            } catch (error: Throwable) {
                log.w(error) { "Dropping invalid external transaction from broadcast queue." }
                storage.deleteSentTransaction(sentTransaction)
                null
            }
        }

        return ownTransactions + externalTransactions
    }

    // Returns false when an own transaction is no longer pending (deleted or mined concurrently),
    // in which case the caller must not queue a getdata task for it. External transactions are not
    // expiry-deleted, so they always record successfully.
    private fun transactionSendStart(transaction: BroadcastTransaction, peers: List<Peer>): Boolean {
        val txHash = transaction.transaction.header.hash.toReversedHex()
        val sentTransaction = if (transaction.external) {
            recordBroadcastAttempt(transaction)
        } else {
            recordBroadcastAttemptIfPending(transaction) ?: run {
                log.d { "Skipping P2P broadcast for tx=$txHash: transaction no longer pending." }
                return false
            }
        }
        ensureDiagnostics(transaction.transaction.header.hash)

        log.d {
            "Broadcast attempt for tx=$txHash sendType=P2P retry=${sentTransaction.retriesCount + 1} peers=${peers.joinToString { it.host }}"
        }
        return true
    }

    private fun queueBroadcastRetry(transaction: BroadcastTransaction) {
        val txHash = transaction.transaction.header.hash.toReversedHex()
        ensureDiagnostics(transaction.transaction.header.hash)
        // While paused the timer would tick without sending anything; resumeNetwork() restarts it.
        if (!networkPaused) {
            timer.startIfNotRunning()
        }
        log.w { "API fallback could not broadcast tx=$txHash because no eligible peers were available. Queued for retry." }
    }

    private fun buildBroadcastAttempt(transaction: BroadcastTransaction, storedTransaction: SentTransaction?): SentTransaction {
        val sentTransaction = storedTransaction ?: sentTransaction(transaction)
        sentTransaction.lastSendTime = System.currentTimeMillis()
        sentTransaction.sendSuccess = false
        updateExternalState(sentTransaction, transaction)
        return sentTransaction
    }

    private fun recordBroadcastAttempt(transaction: BroadcastTransaction): SentTransaction {
        val storedTransaction = storage.getSentTransaction(transaction.transaction.header.hash)
        val sentTransaction = buildBroadcastAttempt(transaction, storedTransaction)

        if (storedTransaction == null) {
            storage.addSentTransaction(sentTransaction)
        } else {
            storage.updateSentTransaction(sentTransaction)
        }

        return sentTransaction
    }

    // Own-transaction-only atomic variant of recordBroadcastAttempt: the pending re-check and the
    // SentTransaction insert happen in one DB transaction (see IStorage.recordBroadcastAttemptIfPending),
    // so it can never write a SentTransaction for a transaction that a concurrent NEW-expiry delete
    // already removed. Returns null when the transaction is no longer pending; callers must not send
    // its bytes in that case.
    private fun recordBroadcastAttemptIfPending(transaction: BroadcastTransaction): SentTransaction? {
        val storedTransaction = storage.getSentTransaction(transaction.transaction.header.hash)
        val sentTransaction = buildBroadcastAttempt(transaction, storedTransaction)
        return sentTransaction.takeIf { storage.recordBroadcastAttemptIfPending(it) }
    }

    private fun queueExternalBroadcast(transaction: BroadcastTransaction) {
        val storedTransaction = storage.getSentTransaction(transaction.transaction.header.hash)
        val sentTransaction = storedTransaction ?: sentTransaction(transaction)
        updateExternalState(sentTransaction, transaction)

        if (storedTransaction == null) {
            storage.addSentTransaction(sentTransaction)
        } else {
            storage.updateSentTransaction(sentTransaction)
        }
    }

    private fun sentTransaction(transaction: BroadcastTransaction): SentTransaction {
        return if (transaction.external) {
            SentTransaction(
                hash = transaction.transaction.header.hash,
                rawTransactionHex = requireNotNull(transaction.rawTransactionHex) { "External transaction raw hex is required" },
            )
        } else {
            SentTransaction(transaction.transaction.header.hash)
        }
    }

    private fun updateExternalState(sentTransaction: SentTransaction, transaction: BroadcastTransaction) {
        if (!transaction.external) {
            return
        }

        sentTransaction.external = true
        sentTransaction.rawTransactionHex = requireNotNull(transaction.rawTransactionHex) { "External transaction raw hex is required" }
    }

    private fun rawHex(transaction: BroadcastTransaction): String {
        return transaction.rawTransactionHex ?: transactionSerializer.serialize(transaction.transaction).toHexString()
    }

    private fun markBroadcastAccepted(transaction: BroadcastTransaction) {
        clearDiagnostics(transaction.transaction.header.hash)

        // For an own transaction, the status must move to RELAYED before its SentTransaction guard
        // row is removed. While status is still NEW, deleteNewExpiredTransactions is only blocked by
        // that row; deleting it first (or failing here before it runs) would leave a window where an
        // already-accepted transaction is NEW with no guard and could be expiry-deleted. Once
        // RELAYED, deleteNewExpiredTransactions (which requires status == NEW) skips it regardless of
        // the row's presence, so there is no unsafe window.
        if (!transaction.external) {
            transactionSyncer.handleRelayed(listOf(transaction.transaction))
        }

        // The pre-accept SentTransaction row (own or external) must not linger: it would otherwise
        // keep showing up in getTransactionsInSendQueue for a transaction that is no longer pending.
        storage.getSentTransaction(transaction.transaction.header.hash)?.let { sentTransaction ->
            storage.deleteSentTransaction(sentTransaction)
        }
    }

    @Synchronized
    private fun transactionSendAttemptCompleted(peer: Peer, task: SendTransactionTask) {
        val transaction = task.transaction
        val txHash = transaction.header.hash.toReversedHex()

        // Resolve tracking state before recording diagnostics: untracked or already-accepted
        // transactions must not leave diagnostics entries or consume the retry budget.
        val sentTransaction = storage.getSentTransaction(transaction.header.hash)
        if (sentTransaction == null || sentTransaction.sendSuccess) {
            return
        }

        when (task.completionReason) {
            SendTransactionTask.CompletionReason.REQUESTED_BY_PEER -> {
                markRequestedByPeer(transaction.header.hash)
                log.i { "Peer ${peer.host} requested tx=$txHash." }
            }

            SendTransactionTask.CompletionReason.TIMEOUT, null -> {
                log.d { "Peer ${peer.host} did not request tx=$txHash before timeout." }
            }
        }

        sentTransaction.retriesCount++
        sentTransaction.sendSuccess = true

        if (sentTransaction.retriesCount >= maxRetriesCount) {
            val state = clearDiagnostics(transaction.header.hash)
            log.w {
                "Broadcast attempts exhausted for tx=$txHash retries=${sentTransaction.retriesCount} " +
                "requestedByPeer=${state?.requestedByPeer == true} rejectedByPeer=${state?.rejectedByPeer == true} " +
                "lastReject=${state?.lastRejectDescription ?: "<none>"}"
            }
            if (!sentTransaction.external) {
                transactionSyncer.handleInvalid(transaction)
            }
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

        log.w {
            "Peer ${peer.host} rejected tx=${rejectedHash.toReversedHex()} code=${rejectMessage.rejectCodeName} reason=${rejectMessage.reason.ifBlank { "<empty>" }}"
        }
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

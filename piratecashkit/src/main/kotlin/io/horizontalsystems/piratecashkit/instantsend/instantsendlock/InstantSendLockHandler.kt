package io.horizontalsystems.piratecashkit.instantsend.instantsendlock

import co.touchlab.kermit.Logger
import io.horizontalsystems.bitcoincore.core.HashBytes
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.models.InventoryItem
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.piratecashkit.IInstantTransactionDelegate
import io.horizontalsystems.piratecashkit.InventoryType
import io.horizontalsystems.piratecashkit.instantsend.ISLockPeerValidator
import io.horizontalsystems.piratecashkit.instantsend.InstantTransactionManager
import io.horizontalsystems.piratecashkit.messages.ISLockMessage
import io.horizontalsystems.piratecashkit.tasks.RequestInstantSendLocksTask
import io.horizontalsystems.piratecashkit.tasks.RequestInstantTransactionsTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InstantSendLockHandler(
        private val instantTransactionManager: InstantTransactionManager,
        private val instantLockManager: InstantSendLockManager,
        private val peerValidator: ISLockPeerValidator,
        private val peerManager: PeerManager,
        private val logTag: String
) {
    private val log = Logger.withTag(logTag)

    data class PendingTransactionRequest(
        val txHash: ByteArray,
        val isLock: ISLockMessage,
        val firstRequestTime: Long,
        val requestedFromPeers: MutableSet<String>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PendingTransactionRequest) return false
            return txHash.contentEquals(other.txHash)
        }

        override fun hashCode(): Int {
            return txHash.contentHashCode()
        }
    }

    var delegate: IInstantTransactionDelegate? = null

    private val pendingTransactionRequests = ConcurrentHashMap<HashBytes, PendingTransactionRequest>()
    private val cleanupExecutor = Executors.newSingleThreadScheduledExecutor()

    init {
        // Schedule periodic cleanup of expired transaction requests
        try {
            cleanupExecutor.scheduleAtFixedRate(
                { cleanupExpiredRequests() },
                TRANSACTION_REQUEST_TIMEOUT_MS,
                TRANSACTION_REQUEST_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (e: Exception) {
            log.e(e) { "Failed to schedule cleanup task for InstantSendLockHandler" }
        }
    }

    fun handle(transactionHash: ByteArray) {
        // Clean up pending request if we were waiting for this transaction
        val hashKey = HashBytes(transactionHash)
        pendingTransactionRequests.remove(hashKey)?.let { request ->
            val waitTime = System.currentTimeMillis() - request.firstRequestTime
            val peerList = request.requestedFromPeers.joinToString(", ")
            log.i { "✓ Proactively requested transaction ${transactionHash.toReversedHex()} received after ${waitTime}ms (requested from: $peerList)" }
        }

        // Check if already marked as instant (may have been done preemptively by peer validation)
        if (instantTransactionManager.isTransactionInstant(transactionHash)) {
            log.d { "Transaction ${transactionHash.toReversedHex()} already marked instant, notifying delegate" }
            delegate?.onUpdateInstant(transactionHash)
            return
        }

        // get relayed lock for inserted transaction and check it
        instantLockManager.takeRelayedLock(transactionHash)?.let { lock ->
            // For relayed locks (from mempool), we don't have peer info
            // so we validate and apply immediately
            validateSendLock(lock)
        }
    }

    fun handle(peer: Peer, isLock: ISLockMessage) {
        // check transaction already not in instant
        if (instantTransactionManager.isTransactionInstant(isLock.txHash)) {
            log.d { "ISLock received but tx ${isLock.txHash.toReversedHex()} already marked instant" }
            return
        }

        // Save ISLock for later if transaction doesn't exist yet
        val txExists = instantTransactionManager.isTransactionExists(isLock.txHash)
        log.d { "ISLock received from ${peer.host} for tx ${isLock.txHash.toReversedHex()}, txExists=$txExists" }
        if (!txExists) {
            instantLockManager.add(isLock)
            log.d { "Saved ISLock in relayedLocks, will validate when tx arrives" }
        }

        // Add peer confirmation and validate when threshold is reached
        // This works even if transaction doesn't exist yet - we'll mark it as instant preemptively
        val isFirstConfirmation = peerValidator.addConfirmation(peer, isLock) { validatedISLock ->
            log.d { "Validation callback triggered for tx ${validatedISLock.txHash.toReversedHex()}" }
            validateSendLock(validatedISLock, txExists)
        }

        log.d { "isFirstConfirmation=$isFirstConfirmation for tx ${isLock.txHash.toReversedHex()}" }

        // Proactively request ISLock from other peers on first confirmation
        if (isFirstConfirmation) {
            requestISLockFromOtherPeers(peer, isLock)
        }
    }

    private fun requestISLockFromOtherPeers(excludePeer: Peer, isLock: ISLockMessage) {
        val availablePeers = peerManager.sorted()
            .filter { it != excludePeer }
            .take(PROACTIVE_REQUEST_PEER_COUNT)

        if (availablePeers.isEmpty()) {
            log.d { "No available peers to proactively request ISLock for tx ${isLock.txHash.toReversedHex()}" }
            return
        }

        val inventoryItem = InventoryItem(InventoryType.MSG_ISDLOCK, isLock.hash)
        val peerHosts = availablePeers.joinToString(", ") { it.host }

        log.d { "Proactively requesting ISLock for tx ${isLock.txHash.toReversedHex()} from ${availablePeers.size} peers: $peerHosts" }

        availablePeers.forEach { peer ->
            peer.addTask(RequestInstantSendLocksTask(listOf(inventoryItem)))
        }
    }

    private fun validateSendLock(isLock: ISLockMessage, txExists: Boolean = true) {
        try {
            log.d { "validateSendLock started for tx: ${isLock.txHash.toReversedHex()}, txExists=$txExists" }

            instantLockManager.validate(isLock)
            log.d { "instantLockManager.validate completed for tx: ${isLock.txHash.toReversedHex()}" }

            // Mark transaction as instant even if it doesn't exist yet
            // This allows immediate spending when the transaction arrives
            instantTransactionManager.makeInstant(isLock.txHash)
            log.d { "instantTransactionManager.makeInstant completed for tx: ${isLock.txHash.toReversedHex()}" }

            // Remove from pending validations if it was being validated through peer confirmations
            peerValidator.removePending(isLock.txHash)

            // If transaction doesn't exist, proactively request it from peers
            if (!txExists) {
                requestTransactionFromPeers(isLock)
            }

            // Only notify delegate if transaction exists (has actual outputs to update)
            if (txExists) {
                delegate?.onUpdateInstant(isLock.txHash)
                log.d { "delegate.onUpdateInstant completed for tx: ${isLock.txHash.toReversedHex()}" }
            } else {
                log.d { "Skipped delegate notification - transaction will be marked instant when it arrives" }
            }

            log.d { "ISLock validated for tx: ${isLock.txHash.toReversedHex()}" }
        } catch (e: Exception) {
            log.e(e) { "Failed to validate InstantSend lock" }
        }
    }

    private fun requestTransactionFromPeers(isLock: ISLockMessage) {
        val hashKey = HashBytes(isLock.txHash)

        // Check if already requested
        if (pendingTransactionRequests.containsKey(hashKey)) {
            log.d { "Transaction ${isLock.txHash.toReversedHex()} already requested, skipping" }
            return
        }

        // Get best available peers
        val availablePeers = peerManager.sorted()
            .take(PROACTIVE_REQUEST_PEER_COUNT)

        if (availablePeers.isEmpty()) {
            log.w { "No available peers to request transaction ${isLock.txHash.toReversedHex()}" }
            return
        }

        val peerHosts = availablePeers.map { it.host }.toMutableSet()
        val peerHostsStr = peerHosts.joinToString(", ")

        // Track the request
        val request = PendingTransactionRequest(
            txHash = isLock.txHash,
            isLock = isLock,
            firstRequestTime = System.currentTimeMillis(),
            requestedFromPeers = peerHosts
        )
        pendingTransactionRequests[hashKey] = request

        log.i { "→ Proactively requesting transaction ${isLock.txHash.toReversedHex()} from ${availablePeers.size} peers: $peerHostsStr" }

        // Send request to each peer
        availablePeers.forEach { peer ->
            log.d { "Adding RequestInstantTransactionsTask to peer ${peer.host}" }
            peer.addTask(RequestInstantTransactionsTask(listOf(isLock.txHash)))
        }
    }

    private fun cleanupExpiredRequests() {
        val now = System.currentTimeMillis()
        val keysToRemove = mutableListOf<HashBytes>()

        pendingTransactionRequests.forEach { (key, request) ->
            val age = now - request.firstRequestTime
            if (age > TRANSACTION_REQUEST_TIMEOUT_MS) {
                log.w {
                    "Transaction request for ${request.txHash.toReversedHex()} timed out after ${age}ms (requested from ${request.requestedFromPeers.size} peers)"
                }
                keysToRemove.add(key)
            }
        }

        keysToRemove.forEach { pendingTransactionRequests.remove(it) }

        if (keysToRemove.isNotEmpty()) {
            log.d { "Cleaned up ${keysToRemove.size} expired transaction request(s)" }
        }
    }

    fun shutdown() {
        cleanupExecutor.shutdown()
    }

    companion object {
        private const val PROACTIVE_REQUEST_PEER_COUNT = 3
        private const val TRANSACTION_REQUEST_TIMEOUT_MS = 30_000L // 30 seconds
    }

}

package io.horizontalsystems.piratecashkit.instantsend

import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.piratecashkit.messages.ISLockMessage
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Validates ISLock messages by requiring confirmations from multiple peers
 * instead of cryptographic BLS signature verification.
 *
 * This approach relies on the assumption that if multiple independent peers
 * send the same ISLock message, it is likely legitimate.
 */
class ISLockPeerValidator(
    private val logTag: String
) {

    data class PeerConfirmation(
        val peerHost: String,
        val timestamp: Long,
        val isLockHash: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PeerConfirmation) return false
            return peerHost == other.peerHost
        }

        override fun hashCode(): Int {
            return peerHost.hashCode()
        }
    }

    data class PendingISLock(
        val isLock: ISLockMessage,
        val confirmations: MutableSet<PeerConfirmation>,
        val firstSeenTimestamp: Long
    )

    private val pendingISLocks = ConcurrentHashMap<String, PendingISLock>()
    private val cleanupExecutor = Executors.newSingleThreadScheduledExecutor()

    init {
        // Schedule periodic cleanup of expired entries
        cleanupExecutor.scheduleAtFixedRate(
            { cleanupExpired() },
            CONFIRMATION_TIMEOUT_MS,
            CONFIRMATION_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )
    }

    /**
     * Add a confirmation from a peer for an ISLock message.
     * If the required number of confirmations is reached, the callback is invoked.
     *
     * @param peer The peer that sent the ISLock
     * @param isLock The ISLock message
     * @param onValidated Callback invoked when validation succeeds
     * @return true if this is the first confirmation for this ISLock, false otherwise
     */
    fun addConfirmation(
        peer: Peer,
        isLock: ISLockMessage,
        onValidated: (ISLockMessage) -> Unit
    ): Boolean {
        val txHashKey = isLock.txHash.toReversedHex()

        val isFirstConfirmation = !pendingISLocks.containsKey(txHashKey)

        val pending = pendingISLocks.getOrPut(txHashKey) {
            Timber.tag(logTag).d("New ISLock for tx $txHashKey from ${peer.host}")
            PendingISLock(
                isLock = isLock,
                confirmations = mutableSetOf(),
                firstSeenTimestamp = System.currentTimeMillis()
            )
        }

        // Note: Different quorums can create different ISLock hashes for the same transaction
        // This is normal in Dash network. We accept all valid ISLocks for the same txHash.
        if (!isLock.hash.contentEquals(pending.isLock.hash)) {
            Timber.tag(logTag)
                .d("Peer ${peer.host} sent ISLock with different hash for tx $txHashKey (different quorum)")
        }

        // Add confirmation from this peer
        val confirmation = PeerConfirmation(
            peerHost = peer.host,
            timestamp = System.currentTimeMillis(),
            isLockHash = isLock.hash
        )

        val isNew = pending.confirmations.add(confirmation)
        if (isNew) {
            Timber.tag(logTag)
                .d("ISLock for tx $txHashKey: ${pending.confirmations.size}/$REQUIRED_PEER_CONFIRMATIONS confirmations (from ${peer.host})")
        }

        // Check if threshold reached
        if (pending.confirmations.size >= REQUIRED_PEER_CONFIRMATIONS) {
            val peerHosts = pending.confirmations.joinToString(", ") { it.peerHost }
            Timber.tag(logTag)
                .i("ISLock for tx $txHashKey validated by ${pending.confirmations.size} peers: $peerHosts")
            pendingISLocks.remove(txHashKey)
            onValidated(isLock)
        }

        return isFirstConfirmation
    }

    /**
     * Remove expired pending ISLocks that didn't receive enough confirmations
     */
    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val keysToRemove = mutableListOf<String>()

        pendingISLocks.forEach { (key, pending) ->
            val age = now - pending.firstSeenTimestamp
            if (age > CONFIRMATION_TIMEOUT_MS) {
                Timber.tag(logTag)
                    .d("ISLock $key expired after ${age}ms with ${pending.confirmations.size}/$REQUIRED_PEER_CONFIRMATIONS confirmations")
                keysToRemove.add(key)
            }
        }

        keysToRemove.forEach { pendingISLocks.remove(it) }

        if (keysToRemove.isNotEmpty()) {
            Timber.tag(logTag).d("Cleaned up ${keysToRemove.size} expired ISLock(s)")
        }
    }

    /**
     * Remove pending ISLock when peer disconnects
     * Note: We keep the confirmation from disconnected peer as it was already received
     */
    fun onPeerDisconnected(peer: Peer) {
        // Optional: Could remove confirmations from this peer if desired
        // For now, we keep them as they were legitimately received
        Timber.tag(logTag).d("Peer ${peer.host} disconnected (confirmations retained)")
    }

    /**
     * Remove pending ISLock when it gets validated through another path (e.g., relayed lock)
     */
    fun removePending(txHash: ByteArray) {
        val txHashKey = txHash.toReversedHex()
        pendingISLocks.remove(txHashKey)?.let {
            Timber.tag(logTag).d("Removed pending ISLock for tx $txHashKey (validated through alternative path)")
        }
    }

    /**
     * Get current pending ISLock count (for debugging/monitoring)
     */
    fun getPendingCount(): Int = pendingISLocks.size

    fun shutdown() {
        cleanupExecutor.shutdown()
    }

    companion object {
        const val REQUIRED_PEER_CONFIRMATIONS = 2
        const val CONFIRMATION_TIMEOUT_MS = 20_000L // 20 seconds
    }
}

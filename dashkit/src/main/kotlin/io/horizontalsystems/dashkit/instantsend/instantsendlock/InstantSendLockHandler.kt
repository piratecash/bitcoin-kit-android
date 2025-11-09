package io.horizontalsystems.dashkit.instantsend.instantsendlock

import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.models.InventoryItem
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.dashkit.IInstantTransactionDelegate
import io.horizontalsystems.dashkit.InventoryType
import io.horizontalsystems.dashkit.instantsend.ISLockPeerValidator
import io.horizontalsystems.dashkit.instantsend.InstantTransactionManager
import io.horizontalsystems.dashkit.messages.ISLockMessage
import io.horizontalsystems.dashkit.tasks.RequestInstantSendLocksTask
import timber.log.Timber

class InstantSendLockHandler(
        private val instantTransactionManager: InstantTransactionManager,
        private val instantLockManager: InstantSendLockManager,
        private val peerValidator: ISLockPeerValidator,
        private val peerManager: PeerManager,
        private val logTag: String
) {

    var delegate: IInstantTransactionDelegate? = null

    fun handle(transactionHash: ByteArray) {
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
            return
        }

        // do nothing if tx doesn't exist
        if (!instantTransactionManager.isTransactionExists(isLock.txHash)) {
            instantLockManager.add(isLock)
            return
        }

        // Add peer confirmation and validate when threshold is reached
        val isFirstConfirmation = peerValidator.addConfirmation(peer, isLock) { validatedISLock ->
            validateSendLock(validatedISLock)
        }

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
            Timber.tag(logTag).d("No available peers to proactively request ISLock for tx ${isLock.txHash.toReversedHex()}")
            return
        }

        val inventoryItem = InventoryItem(InventoryType.MSG_ISDLOCK, isLock.hash)
        val peerHosts = availablePeers.joinToString(", ") { it.host }

        Timber.tag(logTag).d("Proactively requesting ISLock for tx ${isLock.txHash.toReversedHex()} from ${availablePeers.size} peers: $peerHosts")

        availablePeers.forEach { peer ->
            peer.addTask(RequestInstantSendLocksTask(listOf(inventoryItem)))
        }
    }

    private fun validateSendLock(isLock: ISLockMessage) {
        try {
            instantLockManager.validate(isLock)

            instantTransactionManager.makeInstant(isLock.txHash)
            delegate?.onUpdateInstant(isLock.txHash)
            Timber.tag(logTag).d("ISLock validated for tx: ${isLock.txHash.toReversedHex()}")
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Failed to validate InstantSend lock")
        }
    }

    companion object {
        private const val PROACTIVE_REQUEST_PEER_COUNT = 2
    }

}

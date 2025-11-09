package io.horizontalsystems.dashkit.managers

import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.blocks.IPeerSyncListener
import io.horizontalsystems.bitcoincore.core.IInitialDownload
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toReversedByteArray
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.models.BlockHash
import io.horizontalsystems.bitcoincore.network.peer.IPeerTaskHandler
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerGroup
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.dashkit.messages.MasternodeListDiffMessage
import io.horizontalsystems.dashkit.tasks.PeerTaskFactory
import io.horizontalsystems.dashkit.tasks.RequestMasternodeListDiffTask
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MasternodeListSyncer(
    private val bitcoinCore: BitcoinCore,
    private val peerTaskFactory: PeerTaskFactory,
    private val masternodeListManager: MasternodeListManager,
    private val initialBlockDownload: IInitialDownload,
    private val storage: IStorage,
    private val logTag: String
) : IPeerTaskHandler, IPeerSyncListener, PeerGroup.Listener {

    // Track active requests per block hash with associated peers
    private data class ActiveRequest(
        val blockHash: ByteArray,
        val baseBlockHash: ByteArray,
        val peers: MutableSet<Peer> = mutableSetOf(),
        var lastRequestTimestamp: Long = 0L
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ActiveRequest) return false
            return blockHash.contentEquals(other.blockHash)
        }
        override fun hashCode(): Int = blockHash.contentHashCode()
    }

    private data class PendingDiff(
        val diff: MasternodeListDiffMessage,
        val peers: LinkedHashSet<Peer>,
        var lastRequestTimestamp: Long = 0L
    )

    // Track peers currently working on MNLISTDIFF requests
    private val workingPeers = ConcurrentHashMap.newKeySet<Peer>()
    // Track active requests to avoid duplicates
    private val activeRequests = ConcurrentHashMap<String, ActiveRequest>()
    private val peersQueue = Executors.newSingleThreadExecutor()

    // Storage for pending MNLISTDIFF messages with associated peer information
    private val pendingMnlistDiffs = ConcurrentHashMap<String, PendingDiff>()

    private val maxPeersPerRequest = 3
    private val minRequestIntervalMs = 2_000L

    override fun onPeerSynced(peer: Peer) {
        // Attempt to process pending MNLISTDIFF after peer synchronization
        processPendingMnlistDiffs()

        assignNextSyncPeer()
    }

    override fun onPeerDisconnect(peer: Peer, e: Exception?) {
        // Remove peer from working set
        val wasWorking = workingPeers.remove(peer)

        if (wasWorking) {
            Timber.tag(logTag).d("Working peer ${peer.host} disconnected, reassigning tasks")
        }

        // Find and reassign active requests from this peer
        val requestsToReassign = mutableListOf<ActiveRequest>()
        val activeRequestKeysToRemove = mutableListOf<String>()
        activeRequests.entries.forEach { (key, request) ->
            if (request.peers.remove(peer)) {
                requestsToReassign.add(request)
                Timber.tag(logTag)
                    .d("Removing ${peer.host} from active request ${request.blockHash.toReversedHex()}")

                // If no peers left for this request, remove it entirely
                if (request.peers.isEmpty()) {
                    activeRequestKeysToRemove.add(key)
                    Timber.tag(logTag)
                        .d("No peers left for active request ${request.blockHash.toReversedHex()}, removing")
                }
            }
        }

        // Clean up empty active requests
        activeRequestKeysToRemove.forEach { activeRequests.remove(it) }

        // Remove disconnected peer from pending entries
        val keysToRemove = mutableListOf<String>()
        pendingMnlistDiffs.forEach { (key, pending) ->
            if (pending.peers.remove(peer) && pending.peers.isEmpty()) {
                keysToRemove.add(key)
            }
        }
        if (keysToRemove.isNotEmpty()) {
            Timber.tag(logTag)
                .d("Removing ${keysToRemove.size} pending MNLISTDIFF entries after ${peer.host} disconnected")
            keysToRemove.forEach {
                pendingMnlistDiffs.remove(it)
                // Also remove from active requests
                activeRequests.remove(it)
            }
        }

        // Reassign requests that lost peers
        if (requestsToReassign.isNotEmpty() || wasWorking) {
            assignNextSyncPeer()
        }
    }

    private fun assignNextSyncPeer() {
        peersQueue.execute {
            bitcoinCore.lastBlockInfo?.let { lastBlockInfo ->
                val blockHash = lastBlockInfo.headerHash.toReversedByteArray()
                val baseBlockHash = masternodeListManager.baseBlockHash

                if (blockHash.contentEquals(baseBlockHash)) {
                    // Already synced
                    return@execute
                }

                val blockHashKey = blockHash.toReversedHex()

                // Get or create active request for this block
                val activeRequest = activeRequests.getOrPut(blockHashKey) {
                    ActiveRequest(blockHash, baseBlockHash)
                }

                // Check if we already have enough peers working on this request
                if (activeRequest.peers.size >= maxPeersPerRequest) {
                    Timber.tag(logTag)
                        .d("MNLISTDIFF for block $blockHashKey already has ${activeRequest.peers.size} peers working, skipping")
                    return@execute
                }

                // Find available synced peers that are not already working on this request
                val availablePeers = initialBlockDownload.syncedPeers
                    .filter { peer ->
                        peer !in activeRequest.peers &&
                        peer.connected &&
                        workingPeers.size < maxPeersPerRequest * 2 // Global limit on working peers
                    }
                    .take(maxPeersPerRequest - activeRequest.peers.size)

                if (availablePeers.isEmpty()) {
                    Timber.tag(logTag).d("No available peers for MNLISTDIFF request")
                    return@execute
                }

                // Assign tasks to available peers
                availablePeers.forEach { peer ->
                    val task = peerTaskFactory.createRequestMasternodeListDiffTask(
                        baseBlockHash,
                        blockHash,
                        logTag
                    )
                    peer.addTask(task)

                    activeRequest.peers.add(peer)
                    workingPeers.add(peer)

                    Timber.tag(logTag)
                        .d("Assigned MNLISTDIFF request for block $blockHashKey to peer ${peer.host} (${activeRequest.peers.size}/$maxPeersPerRequest)")
                }

                activeRequest.lastRequestTimestamp = System.currentTimeMillis()
            }
        }
    }


    override fun handleCompletedTask(peer: Peer, task: PeerTask): Boolean =
        when (task) {
            is RequestMasternodeListDiffTask -> handleMasternodeListDiffTask(peer, task)
            else -> false
        }

    private fun handleMasternodeListDiffTask(
        peer: Peer,
        task: RequestMasternodeListDiffTask
    ): Boolean {
        // Remove peer from working set
        workingPeers.remove(peer)

        val diffMessage = task.masternodeListDiffMessage

        if (diffMessage == null) {
            Timber.tag(logTag).w("Masternode list diff timed out for ${peer.host}")

            // Remove peer from active request
            val blockHashKey = task.blockHash.toReversedHex()
            activeRequests[blockHashKey]?.let { request ->
                request.peers.remove(peer)
                if (request.peers.isEmpty()) {
                    activeRequests.remove(blockHashKey)
                }
            }

            // Try to assign to another peer
            assignNextSyncPeer()
            return true
        }

        processMasternodeListDiff(diffMessage, peer)
        return true
    }

    private fun processMasternodeListDiff(diffMessage: MasternodeListDiffMessage, peer: Peer) {
        val blockHashKey = diffMessage.blockHash.toReversedHex()

        try {
            masternodeListManager.updateList(diffMessage)

            // Successfully processed - cancel all other pending requests for this block
            activeRequests.remove(blockHashKey)?.let { request ->
                request.peers.forEach { otherPeer ->
                    if (otherPeer != peer) {
                        workingPeers.remove(otherPeer)
                        Timber.tag(logTag)
                            .d("Cancelled redundant MNLISTDIFF request from ${otherPeer.host} (already received from ${peer.host})")
                    }
                }
            }

            pendingMnlistDiffs.remove(blockHashKey)

            Timber.tag(logTag)
                .d("Successfully processed masternode diff for block $blockHashKey from ${peer.host}")

            // Assign next sync peer to continue with next block
            assignNextSyncPeer()
        } catch (error: MasternodeListManager.ValidationError.NoMerkleBlockHeader) {
            // Block not loaded - request it and defer processing
            Timber.tag(logTag)
                .w("Block $blockHashKey not loaded yet, requesting...")

            // Keep in active requests to prevent re-requesting MNLISTDIFF
            // It will be removed when we successfully process or when all peers disconnect

            // Save MNLISTDIFF for later processing
            val pending = pendingMnlistDiffs.getOrPut(blockHashKey) {
                PendingDiff(diffMessage, LinkedHashSet())
            }
            if (pending.peers.add(peer)) {
                while (pending.peers.size > maxPeersPerRequest) {
                    val iterator = pending.peers.iterator()
                    if (iterator.hasNext()) {
                        val removedPeer = iterator.next()
                        iterator.remove()
                        Timber.tag(logTag)
                            .d("Dropping least-recent peer ${removedPeer.host} for pending diff $blockHashKey")
                    } else {
                        break
                    }
                }
            }

            // Request missing block from the new peer (respecting rate limits)
            requestMissingBlockFromPeer(pending, peer)

            // DON'T call assignNextSyncPeer() - we're waiting for block to be downloaded
            // onPeerSynced() will retry pending diffs when block is ready
        } catch (error: MasternodeListManager.ValidationError) {
            Timber.tag(logTag).w(error, "Invalid masternode list diff from ${peer.host}")

            // Remove this peer from active request but keep others
            activeRequests[blockHashKey]?.peers?.remove(peer)

            handlePeerFailure(peer, error)
        } catch (error: QuorumListManager.ValidationError) {
            Timber.tag(logTag).w(error, "Invalid quorum diff from ${peer.host}")

            // Remove this peer from active request but keep others
            activeRequests[blockHashKey]?.peers?.remove(peer)

            handlePeerFailure(peer, error)
        } catch (error: Exception) {
            Timber.tag(logTag)
                .e(error, "Unexpected error while processing masternode diff from ${peer.host}")

            // Remove this peer from active request but keep others
            activeRequests[blockHashKey]?.peers?.remove(peer)

            handlePeerFailure(peer, error)
        }
    }

    /**
     * Ensure block is in download queue
     * If block exists but has empty merkleRoot, add it to the download queue
     * to ensure GetMerkleBlocksTask will download it
     */
    private fun requestMissingBlock(peer: Peer, blockHash: ByteArray) {
        try {
            val existingBlock = storage.getBlock(blockHash)

            when {
                // Case 1: Block complete (has merkleRoot) - trigger immediate retry
                existingBlock != null && existingBlock.merkleRoot.isNotEmpty() -> {
                    Timber.tag(logTag)
                        .d("Block ${blockHash.toReversedHex()} ready, retrying pending MNLISTDIFF now")
                    processPendingMnlistDiffs()
                }

                // Case 2: Block exists but incomplete (empty merkleRoot) - ensure it's queued
                existingBlock != null -> {
                    // Check if block hash is in the download queue
                    val inQueue = storage.hasBlockHash(blockHash)

                    if (!inQueue) {
                        // Not in queue - add it!
                        // This can happen if block placeholder was created from HEADERS
                        // but filtered out by BlockSyncer.addBlockHashes() because it "already exists"
                        val lastSequence = storage.getLastBlockHash()?.sequence ?: 0
                        storage.addBlockHashes(
                            listOf(
                                BlockHash(
                                    headerHash = blockHash,
                                    height = 0,
                                    sequence = lastSequence + 1
                                )
                            )
                        )
                        Timber.tag(logTag)
                            .d("Added incomplete block to download queue: ${blockHash.toReversedHex()}")
                    } else {
                        Timber.tag(logTag)
                            .d("Block already in queue, waiting for download: ${blockHash.toReversedHex()}")
                    }
                }

                // Case 3: Block doesn't exist at all - it will be added through normal sync
                else -> {
                    Timber.tag(logTag)
                        .d("Block ${blockHash.toReversedHex()} not found, will be synced normally")
                }
            }

            // Block will be downloaded by GetMerkleBlocksTask
            // onPeerSynced() will retry pending MNLISTDIFF when ready
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Failed to ensure block in download queue")
        }
    }

    /**
     * Process pending MNLISTDIFF messages
     * Called after peer synchronization (when blocks are already loaded)
     */
    private fun processPendingMnlistDiffs() {
        if (pendingMnlistDiffs.isEmpty()) {
            return
        }

        Timber.tag(logTag).d("Processing ${pendingMnlistDiffs.size} pending masternode diffs...")

        val entriesSnapshot = pendingMnlistDiffs.entries.toList()
        val keysToRemove = mutableListOf<String>()

        for ((blockHashKey, pending) in entriesSnapshot) {
            val diffMessage = pending.diff

            try {
                masternodeListManager.updateList(diffMessage)
                keysToRemove.add(blockHashKey)  // Successfully processed - schedule removal

                // Remove from active requests since we're done with this block
                activeRequests.remove(blockHashKey)

                Timber.tag(logTag)
                    .d("Successfully processed pending masternode diff for block $blockHashKey")
            } catch (error: MasternodeListManager.ValidationError.NoMerkleBlockHeader) {
                // Block still not loaded - keep in queue
                Timber.tag(logTag)
                    .d("Block $blockHashKey still not loaded for pending masternode diff")
                val candidatePeer = pending.peers.firstOrNull { it.connected }
                if (candidatePeer == null) {
                    Timber.tag(logTag)
                        .d("No connected peers available for pending diff $blockHashKey")
                    if (pending.peers.isEmpty()) {
                        keysToRemove.add(blockHashKey)
                        // Remove from active requests since we have no peers to help
                        activeRequests.remove(blockHashKey)
                    }
                } else {
                    requestMissingBlockFromPeer(pending, candidatePeer)
                }
            } catch (error: Exception) {
                // Other error - remove from queue and log
                keysToRemove.add(blockHashKey)

                // Remove from active requests since we're giving up on this block
                activeRequests.remove(blockHashKey)

                Timber.tag(logTag)
                    .e(error, "Failed to process pending masternode diff for block $blockHashKey")
            }
        }

        keysToRemove.forEach { pendingMnlistDiffs.remove(it) }

        // If we successfully processed any pending diffs, try to continue syncing
        if (keysToRemove.isNotEmpty()) {
            assignNextSyncPeer()
        }
    }

    private fun requestMissingBlockFromPeer(pending: PendingDiff, peer: Peer) {
        if (!peer.connected) {
            Timber.tag(logTag)
                .d("Peer ${peer.host} not connected, skipping block request for diff ${pending.diff.blockHash.toReversedHex()}")
            return
        }
        val now = System.currentTimeMillis()
        val shouldEnforceRateLimit = pending.peers.size >= maxPeersPerRequest
        if (shouldEnforceRateLimit && now - pending.lastRequestTimestamp < minRequestIntervalMs) {
            Timber.tag(logTag)
                .d("Recently requested block ${pending.diff.blockHash.toReversedHex()}, waiting before retry")
            return
        }
        pending.lastRequestTimestamp = now
        requestMissingBlock(peer, pending.diff.blockHash)
    }

    private fun handlePeerFailure(peer: Peer, error: Exception) {
        workingPeers.remove(peer)
        peer.close(error)
        // assignNextSyncPeer will be called via onPeerDisconnect
    }

}

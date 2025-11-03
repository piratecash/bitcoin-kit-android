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

    @Volatile
    private var workingPeer: Peer? = null
    private val peersQueue = Executors.newSingleThreadExecutor()

    // Storage for pending MNLISTDIFF messages with associated peer information
    private val pendingMnlistDiffs = ConcurrentHashMap<String, Pair<MasternodeListDiffMessage, Peer>>()

    override fun onPeerSynced(peer: Peer) {
        // Attempt to process pending MNLISTDIFF after peer synchronization
        processPendingMnlistDiffs()

        assignNextSyncPeer()
    }

    override fun onPeerDisconnect(peer: Peer, e: Exception?) {
        if (peer == workingPeer) {
            workingPeer = null
            assignNextSyncPeer()
        }

        // Remove pending MNLISTDIFF for disconnected peer
        val keysToRemove = pendingMnlistDiffs.filter { it.value.second == peer }.map { it.key }
        if (keysToRemove.isNotEmpty()) {
            Timber.tag(logTag).d("Removing ${keysToRemove.size} pending MNLISTDIFF for disconnected peer ${peer.host}")
            keysToRemove.forEach { pendingMnlistDiffs.remove(it) }
        }
    }

    private fun assignNextSyncPeer() {
        peersQueue.execute {
            if (workingPeer == null) {
                bitcoinCore.lastBlockInfo?.let { lastBlockInfo ->
                    initialBlockDownload.syncedPeers.firstOrNull()?.let { syncedPeer ->
                        val blockHash = lastBlockInfo.headerHash.toReversedByteArray()
                        val baseBlockHash = masternodeListManager.baseBlockHash

                        if (!blockHash.contentEquals(baseBlockHash)) {
                            val task = peerTaskFactory.createRequestMasternodeListDiffTask(
                                baseBlockHash,
                                blockHash,
                                logTag
                            )
                            syncedPeer.addTask(task)

                            workingPeer = syncedPeer
                        }
                    }
                }
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
        if (peer != workingPeer) {
            Timber.tag(logTag).d("Ignoring masternode diff from non-working peer ${peer.host}")
            return true
        }

        val diffMessage = task.masternodeListDiffMessage

        if (diffMessage == null) {
            Timber.tag(logTag).w("Masternode list diff timed out for ${peer.host}")
            clearWorkingPeer()
            return true
        }

        processMasternodeListDiff(diffMessage, peer)
        return true
    }

    private fun processMasternodeListDiff(diffMessage: MasternodeListDiffMessage, peer: Peer) {
        try {
            masternodeListManager.updateList(diffMessage)
            clearWorkingPeer()
            Timber.tag(logTag).d("Successfully processed masternode diff for block ${diffMessage.blockHash.toReversedHex()}")
        } catch (error: MasternodeListManager.ValidationError.NoMerkleBlockHeader) {
            // Block not loaded - request it and defer processing
            Timber.tag(logTag).w("Block ${diffMessage.blockHash.toReversedHex()} not loaded yet, requesting...")

            // Save MNLISTDIFF for later processing
            val blockHashKey = diffMessage.blockHash.toReversedHex()
            pendingMnlistDiffs[blockHashKey] = Pair(diffMessage, peer)

            // Request missing block from the same peer
            requestMissingBlock(peer, diffMessage.blockHash)

            // Continue working (don't close the peer)
            clearWorkingPeer()
        } catch (error: MasternodeListManager.ValidationError) {
            Timber.tag(logTag).w(error, "Invalid masternode list diff from ${peer.host}")
            handlePeerFailure(peer, error)
        } catch (error: QuorumListManager.ValidationError) {
            Timber.tag(logTag).w(error, "Invalid quorum diff from ${peer.host}")
            handlePeerFailure(peer, error)
        } catch (error: Exception) {
            Timber.tag(logTag).e(error, "Unexpected error while processing masternode diff from ${peer.host}")
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
                    Timber.tag(logTag).d("Block ${blockHash.toReversedHex()} ready, retrying pending MNLISTDIFF now")
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
                        storage.addBlockHashes(listOf(
                            BlockHash(
                                headerHash = blockHash,
                                height = 0,
                                sequence = lastSequence + 1
                            )
                        ))
                        Timber.tag(logTag).d("Added incomplete block to download queue: ${blockHash.toReversedHex()}")
                    } else {
                        Timber.tag(logTag).d("Block already in queue, waiting for download: ${blockHash.toReversedHex()}")
                    }
                }

                // Case 3: Block doesn't exist at all - it will be added through normal sync
                else -> {
                    Timber.tag(logTag).d("Block ${blockHash.toReversedHex()} not found, will be synced normally")
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

        for ((blockHashKey, value) in entriesSnapshot) {
            val (diffMessage, peer) = value

            try {
                masternodeListManager.updateList(diffMessage)
                keysToRemove.add(blockHashKey)  // Successfully processed - schedule removal
                Timber.tag(logTag).d("Successfully processed pending masternode diff for block $blockHashKey")
            } catch (error: MasternodeListManager.ValidationError.NoMerkleBlockHeader) {
                // Block still not loaded - keep in queue
                Timber.tag(logTag).d("Block $blockHashKey still not loaded for pending masternode diff")
            } catch (error: Exception) {
                // Other error - remove from queue and log
                keysToRemove.add(blockHashKey)
                Timber.tag(logTag).e(error, "Failed to process pending masternode diff for block $blockHashKey")
            }
        }

        keysToRemove.forEach { pendingMnlistDiffs.remove(it) }
    }

    private fun clearWorkingPeer(reassign: Boolean = true) {
        workingPeer = null
        if (reassign) {
            assignNextSyncPeer()
        }
    }

    private fun handlePeerFailure(peer: Peer, error: Exception) {
        clearWorkingPeer(reassign = false)
        peer.close(error)
    }

}

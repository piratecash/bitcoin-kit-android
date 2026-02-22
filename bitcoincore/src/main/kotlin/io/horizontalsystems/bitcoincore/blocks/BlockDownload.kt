package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.core.IBlockSyncListener
import io.horizontalsystems.bitcoincore.core.IInitialDownload
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.models.InventoryItem
import io.horizontalsystems.bitcoincore.models.MerkleBlock
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.network.peer.task.GetMerkleBlocksTask
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.logging.Logger

class BlockDownload(
    private var blockSyncer: BlockSyncer,
    private val peerManager: PeerManager,
    private val merkleBlockExtractor: MerkleBlockExtractor,
    private val requestUnknownBlocks: Boolean,
    private val logTag: String
) : IInitialDownload, GetMerkleBlocksTask.MerkleBlockHandler {

    override var listener: IBlockSyncListener? = null
    override val syncedPeers = CopyOnWriteArrayList<Peer>()
    private val peerSyncListeners = CopyOnWriteArrayList<IPeerSyncListener>()
    private val peerSwitchMinimumRatio = 1.5

    @Volatile
    override var syncPeer: Peer? = null
    @Volatile
    private var selectNewPeer = false
    private val peersQueue = Executors.newSingleThreadExecutor()
    private val logger = Logger.getLogger("BlockDownload")

    private class PeerSyncState(@Volatile var synced: Boolean = false)
    private val peerSyncStates = ConcurrentHashMap<String, PeerSyncState>()

    private fun getPeerSyncState(peer: Peer): PeerSyncState {
        return peerSyncStates.getOrPut(peer.host) { PeerSyncState() }
    }

    private var minMerkleBlocks = 500.0
    private var minTransactions = 50_000.0
    private var minReceiveBytes = 100_000.0
    private var slowPeersDisconnected = 0

    override fun addPeerSyncListener(peerSyncListener: IPeerSyncListener) {
        peerSyncListeners.add(peerSyncListener)
    }

    override fun handleInventoryItems(peer: Peer, inventoryItems: List<InventoryItem>) {
        val state = getPeerSyncState(peer)
        if (state.synced && inventoryItems.any { it.type == InventoryItem.MSG_BLOCK }) {
            state.synced = false
            peer.synced = false
            syncedPeers.remove(peer)
            if (requestUnknownBlocks) {
                blockSyncer.addBlockHashes(inventoryItems.filter { it.type == InventoryItem.MSG_BLOCK }
                    .map { it.hash })
            }

            assignNextSyncPeer()
        }
    }

    override fun handleCompletedTask(peer: Peer, task: PeerTask): Boolean {
        if (task.owner != null && task.owner !== this) return false

        return when (task) {
            is GetMerkleBlocksTask -> {
                blockSyncer.downloadIterationCompleted()
                true
            }

            else -> false
        }
    }

    override fun handleMerkleBlock(merkleBlock: MerkleBlock) {
        val maxBlockHeight = syncPeer?.announcedLastBlockHeight ?: 0
        blockSyncer.handleMerkleBlock(merkleBlock, maxBlockHeight)
    }

    override fun onStart() {
        blockSyncer.prepareForDownload()
    }

    override fun onStop() = Unit

    override fun onRefresh() {
        if (syncPeer == null) {
            peerManager.connected().forEach { peer ->
                getPeerSyncState(peer).synced = false
                peer.synced = false
            }

            assignNextSyncPeer()
        }
    }

    override fun onPeerCreate(peer: Peer) {
        peer.localBestBlockHeight = blockSyncer.localDownloadedBestBlockHeight
    }

    override fun onPeerConnect(peer: Peer) {
        syncPeer?.let {
            if (it.connectionTime > peer.connectionTime * peerSwitchMinimumRatio) {
                selectNewPeer = true
            }
        }
        assignNextSyncPeer()
    }

    override fun onPeerReady(peer: Peer) {
        if (peer == syncPeer) {
            downloadBlockchain()
        }
    }

    override fun onPeerDisconnect(peer: Peer, e: Exception?) {
        if (e is GetMerkleBlocksTask.PeerTooSlow) {
            slowPeersDisconnected += 1

            if (slowPeersDisconnected >= 3) {
                slowPeersDisconnected = 0
                minMerkleBlocks /= 3
                minTransactions /= 3
                minReceiveBytes /= 3
            }
        }

        peerSyncStates.remove(peer.host)
        syncedPeers.remove(peer)

        if (peer == syncPeer) {
            syncPeer = null
            blockSyncer.downloadFailed()
            assignNextSyncPeer()
        }
    }

    private fun assignNextSyncPeer() {
        peersQueue.execute {
            if (syncPeer == null) {
                val notSyncedPeers = peerManager.sorted().filter { !getPeerSyncState(it).synced }
                if (notSyncedPeers.isEmpty()) {
                    peerSyncListeners.forEach { it.onAllPeersSynced() }
                }

                notSyncedPeers.firstOrNull { it.ready }?.let { nonSyncedPeer ->
                    syncPeer = nonSyncedPeer
                    blockSyncer.downloadStarted()

                    logger.info("$logTag: Start syncing peer ${nonSyncedPeer.host}")

                    downloadBlockchain()
                }
            }
        }
    }

    private fun downloadBlockchain() {
        syncPeer?.let { peer ->
            if (!peer.ready) return

            if (selectNewPeer) {
                selectNewPeer = false
                blockSyncer.downloadCompleted()
                syncPeer = null
                assignNextSyncPeer()
                return
            }

            val state = getPeerSyncState(peer)

            // Need to request all blocks to resolve orphaned blocks
            (blockSyncer.getOrphanParents()).let {
                if (!it.isEmpty()) {
                    logger.info("$logTag: Requesting orphan parents (${it.size} [${it[0].headerHash.toHexString()}, ...]")
                    val task = GetMerkleBlocksTask(
                        hashes = it,
                        merkleBlockHandler = this,
                        merkleBlockExtractor = merkleBlockExtractor,
                        minMerkleBlocks = minMerkleBlocks,
                        minTransactions = minTransactions,
                        minReceiveBytes = minReceiveBytes,
                        logTag = logTag
                    )
                    task.owner = this
                    peer.addTask(task)
                }
            }

            val blockHashes = blockSyncer.getBlockHashes(limit = 50)
            if (blockHashes.isEmpty()) {
                state.synced = true
                peer.synced = true
            } else {
                val task = GetMerkleBlocksTask(
                    hashes = blockHashes,
                    merkleBlockHandler = this,
                    merkleBlockExtractor = merkleBlockExtractor,
                    minMerkleBlocks = minMerkleBlocks,
                    minTransactions = minTransactions,
                    minReceiveBytes = minReceiveBytes,
                    logTag = logTag
                )
                task.owner = this
                peer.addTask(task)
            }

            if (state.synced) {

                syncedPeers.add(peer)

                blockSyncer.downloadCompleted()
                peer.sendMempoolMessage()
                logger.info("$logTag: Peer synced ${peer.host}")
                syncPeer = null
                assignNextSyncPeer()
                peerSyncListeners.forEach { it.onPeerSynced(peer) }

                listener?.onBlockSyncFinished()
            }
        }
    }

}

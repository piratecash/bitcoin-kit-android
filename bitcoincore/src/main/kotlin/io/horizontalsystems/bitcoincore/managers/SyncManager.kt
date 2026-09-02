package io.horizontalsystems.bitcoincore.managers

import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.BitcoinCore.KitState
import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.bitcoincore.core.IApiSyncer
import io.horizontalsystems.bitcoincore.core.IApiSyncerListener
import io.horizontalsystems.bitcoincore.core.IBlockSyncListener
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IConnectionManagerListener
import io.horizontalsystems.bitcoincore.core.IKitStateListener
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerGroup
import io.horizontalsystems.bitcoincore.transactions.PendingTransactionReconciler
import kotlin.math.max

class SyncManager(
    private val connectionManager: IConnectionManager,
    private val apiSyncer: IApiSyncer,
    private val peerGroup: PeerGroup,
    private val storage: IStorage,
    private val syncMode: SyncMode,
    bestBlockHeight: Int,
    private val peerSize: Int,
    private val pendingTransactionReconciler: PendingTransactionReconciler
) : IApiSyncerListener, IConnectionManagerListener, IBlockSyncListener, PeerGroup.Listener {

    init {
        pendingTransactionReconciler.onConfirmedBlocksFound = ::onPendingTransactionBlocksFound
    }

    var listener: IKitStateListener? = null

    var syncState: KitState = KitState.NotSynced(BitcoinCore.StateError.NotStarted())
        private set(value) {
            if (value != field) {
                field = value

                listener?.onKitStateUpdate(field)
            }
        }

    private val syncIdle: Boolean
        get() = syncState.let {
            it is KitState.NotSynced && it.exception !is BitcoinCore.StateError.NotStarted
        }

    // Not derived from syncState: the initial NotStarted is indistinguishable from a stopped one,
    // and callbacks arrive on the syncer's own thread.
    @Volatile
    private var stopped = false

    private var initialBestBlockHeight = bestBlockHeight
    private var currentBestBlockHeight = bestBlockHeight
    private var foundTransactionsCount = 0
    private var forceAddedBlocksTotal: Int = 0
    // Tracks whether this SyncManager owns an outstanding peerGroup.start() call.
    // The peerGroup may be a SharedPeerGroup ref-counted across kits sharing the
    // same wallet/network (BIP44 + BIP84, MWEB, etc.), so stop() must only
    // decrement the count when this manager actually incremented it.
    @Volatile
    private var peerGroupStarted = false

    private fun startSync() {
        if (apiSyncer.willSync) {
            startInitialSync()
        } else {
            startPeerGroup()
        }
    }

    private fun startInitialSync() {
        syncState = KitState.ApiSyncing(0)
        apiSyncer.sync()
    }

    private fun startPeerGroup() {
        syncState = KitState.Syncing(0.0, BitcoinCore.SyncSubstatus.WaitingForPeers(0, peerSize))
        acquirePeerGroupIfNeeded()
    }

    private fun acquirePeerGroupIfNeeded() {
        if (!peerGroupStarted) {
            peerGroup.start()
            peerGroupStarted = true
        }
        // stop() may have run between a callback's `stopped` check and this acquisition, in which
        // case it saw peerGroupStarted == false and skipped teardown — so undo it here instead.
        if (stopped) stopPeerGroupIfStarted()
    }

    private fun stopPeerGroupIfStarted() {
        if (peerGroupStarted) {
            peerGroup.stop()
            peerGroupStarted = false
        }
    }

    fun start() {
        // While stopped nothing is running by definition, so the state guards below are skipped:
        // a callback that slipped past stop() could have left a stale Syncing/Synced state, and
        // honouring it would turn the resume after pauseNetwork() into a no-op.
        if (!stopped) {
            if (syncMode is SyncMode.Blockchair) {
                when (syncState) {
                    is KitState.ApiSyncing,
                    is KitState.Syncing -> return

                    else -> Unit
                }
            } else {
                if (syncState !is KitState.NotSynced) return
            }
        }

        stopped = false
        pendingTransactionReconciler.start()

        if (connectionManager.isConnected) {
            startSync()
            pendingTransactionReconciler.reconcileAsync()
        } else {
            syncState = KitState.NotSynced(BitcoinCore.StateError.NoInternet())
        }
    }

    fun stop() {
        // Always tear down api syncer and reconciler. PeerGroup is ref-counted via
        // SharedPeerGroup on Bitcoin/Litecoin (BIP44+BIP84+MWEB share one group),
        // so we only call stop() on it if this manager itself started it — otherwise
        // we would decrement another kit's ref count and leak/kill its connection.
        // Previously stop() also missed peerGroup teardown when the kit was halted
        // from any NotSynced state (e.g. user switched wallets before the first
        // sync completed), leaving peer connections and log spam alive in the
        // background.
        // The flag goes up first so that a syncer callback already past its own cancellation check
        // is rejected by the guards below instead of resurrecting the sync we are tearing down.
        stopped = true
        apiSyncer.terminate()
        stopPeerGroupIfStarted()
        pendingTransactionReconciler.stop()
        syncState = KitState.NotSynced(BitcoinCore.StateError.NotStarted())
    }

    //
    // ConnectionManager Listener
    //

    override fun onConnectionChange(isConnected: Boolean) {
        // Connectivity changes constantly while paused — that is the offline scenario itself — and
        // the returning network would otherwise restart the whole sync behind pauseNetwork().
        if (stopped) return

        if (isConnected && syncIdle) {
            startSync()
        } else if (!isConnected && (syncState is KitState.Syncing || syncState is KitState.Synced)) {
            stopPeerGroupIfStarted()
            syncState = KitState.NotSynced(BitcoinCore.StateError.NoInternet())
        }
    }

    //
    // IApiSyncerListener
    //

    // API syncers do blocking, uninterruptible network calls, so their callbacks can arrive after
    // stop(). Acting on one would move the state out of NotStarted and make the next start() —
    // the resume after pauseNetwork() — a no-op.

    override fun onSyncSuccess() {
        if (stopped) return

        forceAddedBlocksTotal = storage.getApiBlockHashesCount()

        if (peerGroup.running) {
            // A sibling kit on the same SharedPeerGroup may have started it already.
            // We are about to use it logically (Synced / refresh), so we must acquire
            // our own ref count — otherwise the sibling's stop() would silently kill
            // the peer group while this kit is still considered Synced.
            acquirePeerGroupIfNeeded()
            if (foundTransactionsCount > 0) {
                foundTransactionsCount = 0
                syncState = KitState.Syncing(0.0, BitcoinCore.SyncSubstatus.WaitingForPeers(
                    peerGroup.getPeerManager().connected().size, peerSize
                ))
                peerGroup.refresh()
            } else {
                syncState = KitState.Synced
            }
        } else {
            startPeerGroup()
        }
    }

    override fun onSyncFailed(error: Throwable) {
        if (stopped) return

        syncState = KitState.NotSynced(error)
    }

    override fun onTransactionsFound(count: Int) {
        if (stopped) return

        foundTransactionsCount += count
        syncState = KitState.ApiSyncing(foundTransactionsCount)
    }

    //
    // IBlockSyncListener implementations
    //

    override fun onCurrentBestBlockHeightUpdate(height: Int, maxBlockHeight: Int) {
        if (stopped || !connectionManager.isConnected) return

        currentBestBlockHeight = max(currentBestBlockHeight, height)

        val blocksDownloaded = currentBestBlockHeight - initialBestBlockHeight
        val allBlocksToDownload = maxBlockHeight - initialBestBlockHeight

        val progress = when {
            allBlocksToDownload <= 0 -> 1.0
            else -> blocksDownloaded / allBlocksToDownload.toDouble()
        }

        syncState = if (progress >= 1) {
            KitState.Synced
        } else {
            KitState.Syncing(progress, maxBlockHeight = maxBlockHeight)
        }
    }

    override fun onBlockForceAdded() {
        if (stopped) return

        if (syncMode !is SyncMode.Blockchair) {
            syncState = KitState.Syncing(0.0)
            return
        }

        val forceAddedBlocks = forceAddedBlocksTotal - storage.getApiBlockHashesCount()
        syncState = when {
            forceAddedBlocks >= forceAddedBlocksTotal -> {
                KitState.Synced
            }

            else -> {
                KitState.Syncing(forceAddedBlocks / forceAddedBlocksTotal.toDouble())
            }
        }
    }

    override fun onBlockSyncFinished() {
        if (stopped) return

        syncState = KitState.Synced
        pendingTransactionReconciler.reconcileAsync()
    }

    private fun onPendingTransactionBlocksFound() {
        if (stopped) return

        forceAddedBlocksTotal = storage.getApiBlockHashesCount()
        if (connectionManager.isConnected && syncState is KitState.Synced) {
            startSync()
        }
    }

    //
    // PeerGroup.Listener
    //

    override fun onPeerConnect(peer: Peer) = updateWaitingForPeersCount()

    override fun onPeerDisconnect(peer: Peer, e: Exception?) = updateWaitingForPeersCount()

    private fun updateWaitingForPeersCount() {
        val state = syncState
        if (state is KitState.Syncing && state.substatus is BitcoinCore.SyncSubstatus.WaitingForPeers) {
            val connected = peerGroup.getPeerManager().connected().size
            syncState = KitState.Syncing(0.0, BitcoinCore.SyncSubstatus.WaitingForPeers(connected, peerSize))
        }
    }
}

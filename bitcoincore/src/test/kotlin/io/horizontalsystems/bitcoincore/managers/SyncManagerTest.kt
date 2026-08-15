package io.horizontalsystems.bitcoincore.managers

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.bitcoincore.core.IApiSyncer
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.network.peer.PeerGroup
import io.horizontalsystems.bitcoincore.transactions.PendingTransactionReconciler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncManagerTest {

    private lateinit var connectionManager: IConnectionManager
    private lateinit var apiSyncer: IApiSyncer
    private lateinit var peerGroup: PeerGroup
    private lateinit var storage: IStorage
    private lateinit var pendingTransactionReconciler: PendingTransactionReconciler

    private lateinit var syncManager: SyncManager

    @Before
    fun setup() {
        connectionManager = mock()
        apiSyncer = mock()
        peerGroup = mock()
        storage = mock()
        pendingTransactionReconciler = mock()

        whenever(connectionManager.isConnected).thenReturn(true)
        // apiSyncer.willSync = false makes start() go straight to startPeerGroup()
        // when we explicitly want to drive the manager into Syncing/Synced states.
        whenever(apiSyncer.willSync).thenReturn(false)

        syncManager = createSyncManager()
    }

    private fun createSyncManager(syncMode: SyncMode = SyncMode.Api()): SyncManager =
        SyncManager(
            connectionManager = connectionManager,
            apiSyncer = apiSyncer,
            peerGroup = peerGroup,
            storage = storage,
            syncMode = syncMode,
            bestBlockHeight = 0,
            peerSize = 1,
            pendingTransactionReconciler = pendingTransactionReconciler
        )

    // --- stop() must always tear down owned components -------------------

    @Test
    fun stop_alwaysTerminatesApiSyncerAndReconciler() {
        syncManager.stop()

        verify(apiSyncer).terminate()
        verify(pendingTransactionReconciler).stop()
    }

    @Test
    fun stop_resetsStateToNotStarted() {
        syncManager.onBlockSyncFinished()

        syncManager.stop()

        val state = syncManager.syncState
        assertTrue(state is BitcoinCore.KitState.NotSynced)
        assertTrue((state as BitcoinCore.KitState.NotSynced).exception is BitcoinCore.StateError.NotStarted)
    }

    // --- stop() must NOT touch peerGroup when this manager never started it ---
    // (Bitcoin/Litecoin share a ref-counted SharedPeerGroup across kits — calling
    //  stop() without a matching start() would let one kit silently kill the
    //  peer group of its siblings.)

    @Test
    fun stop_peerGroupNeverStartedFromInitialState_doesNotStopPeerGroup() {
        syncManager.stop()

        verify(peerGroup, never()).stop()
    }

    @Test
    fun stop_peerGroupNeverStartedAfterSyncFailed_doesNotStopPeerGroup() {
        syncManager.onSyncFailed(RuntimeException("api error"))

        syncManager.stop()

        verify(peerGroup, never()).stop()
    }

    @Test
    fun stop_peerGroupNeverStartedAfterNoInternet_doesNotStopPeerGroup() {
        syncManager.onSyncFailed(BitcoinCore.StateError.NoInternet())

        syncManager.stop()

        verify(peerGroup, never()).stop()
    }

    @Test
    fun stop_apiSyncingStateBeforePeerGroupStart_doesNotStopPeerGroup() {
        syncManager.onTransactionsFound(5)
        assertTrue(syncManager.syncState is BitcoinCore.KitState.ApiSyncing)

        syncManager.stop()

        verify(peerGroup, never()).stop()
    }

    // --- stop() must stop peerGroup when this manager actually started it ---

    @Test
    fun stop_afterPeerGroupStartedViaApiSyncerCallback_stopsPeerGroup() {
        whenever(peerGroup.running).thenReturn(false)
        // apiSyncer finishes -> SyncManager calls startPeerGroup() -> peerGroup.start()
        syncManager.onSyncSuccess()

        syncManager.stop()

        verify(peerGroup).start()
        verify(peerGroup).stop()
    }

    @Test
    fun stop_afterDirectStartWithoutApiSync_stopsPeerGroup() {
        // When apiSyncer reports it won't sync, start() goes straight to startPeerGroup().
        syncManager.start()

        syncManager.stop()

        verify(peerGroup).start()
        verify(peerGroup).stop()
    }

    // --- onConnectionChange disconnect path keeps ownership tracking honest ---

    @Test
    fun stop_afterConnectionLostStoppedPeerGroup_doesNotStopPeerGroupTwice() {
        // Path: start() -> peerGroup.start() (count owned by us)
        //       onConnectionChange(false) -> peerGroup.stop() (ownership released)
        //       stop() -> peerGroup.stop() must NOT be called again.
        syncManager.start()
        verify(peerGroup).start()

        whenever(connectionManager.isConnected).thenReturn(false)
        syncManager.onConnectionChange(false)

        syncManager.stop()

        verify(peerGroup, times(1)).stop()
    }

    @Test
    fun stop_calledTwice_stopsPeerGroupOnlyOnce() {
        syncManager.start()

        syncManager.stop()
        syncManager.stop()

        verify(peerGroup, times(1)).stop()
        // apiSyncer.terminate() and reconciler.stop() are safe to call repeatedly
        // and we keep doing so to guarantee a quiescent state.
        verify(apiSyncer, times(2)).terminate()
        verify(pendingTransactionReconciler, times(2)).stop()
    }

    @Test
    fun start_afterStop_startsPeerGroupAgain() {
        // Lifecycle: start -> stop -> start. The second start must increment the
        // peer-group counter exactly once (covers SharedPeerGroup ref-counting).
        syncManager.start()
        syncManager.stop()

        syncManager.start()

        verify(peerGroup, times(2)).start()
    }

    // --- api syncer callbacks arriving after stop() must not resurrect the sync ---
    // (The syncer's network calls block uninterruptibly, so a callback can land after
    //  pauseNetwork(); any state but NotStarted makes the resuming start() an early return.)

    @Test
    fun onTransactionsFound_afterStop_keepsNotStartedState() {
        syncManager.start()
        syncManager.stop()

        syncManager.onTransactionsFound(5)

        val state = syncManager.syncState
        assertTrue(state is BitcoinCore.KitState.NotSynced)
        assertTrue((state as BitcoinCore.KitState.NotSynced).exception is BitcoinCore.StateError.NotStarted)
    }

    @Test
    fun onSyncSuccess_afterStop_doesNotRestartPeerGroup() {
        syncManager.start()
        syncManager.stop()

        syncManager.onSyncSuccess()

        verify(peerGroup, times(1)).start()
    }

    // --- ownership acquisition when SharedPeerGroup is already running by a sibling ---
    // (BIP44 kit hits apiSyncer.onSyncSuccess() while BIP84 already runs the shared
    //  peer group. Without explicit acquire, BIP44 logically uses the group without
    //  bumping startCount — once BIP84 stops, the shared peer group dies and BIP44
    //  silently ends up Synced without P2P.)

    @Test
    fun onSyncSuccess_peerGroupAlreadyRunningWithoutFoundTransactions_acquiresOwnership() {
        whenever(peerGroup.running).thenReturn(true)

        syncManager.onSyncSuccess()

        verify(peerGroup).start()
        assertEquals(BitcoinCore.KitState.Synced, syncManager.syncState)
    }

    @Test
    fun onSyncSuccess_peerGroupAlreadyRunningWithFoundTransactions_acquiresOwnership() {
        whenever(peerGroup.running).thenReturn(true)
        whenever(peerGroup.getPeerManager()).thenReturn(mock())
        syncManager.onTransactionsFound(3)

        syncManager.onSyncSuccess()

        verify(peerGroup).start()
        verify(peerGroup).refresh()
    }

    @Test
    fun stop_afterAcquiringAlreadyRunningPeerGroup_stopsPeerGroup() {
        whenever(peerGroup.running).thenReturn(true)
        syncManager.onSyncSuccess()

        syncManager.stop()

        verify(peerGroup).start()
        verify(peerGroup).stop()
    }

    @Test
    fun onSyncSuccess_calledTwiceWhenPeerGroupRunning_acquiresOwnershipExactlyOnce() {
        whenever(peerGroup.running).thenReturn(true)

        syncManager.onSyncSuccess()
        syncManager.onSyncSuccess()

        verify(peerGroup, times(1)).start()
    }

    // --- offline start() through the real path (not just onSyncFailed) ---

    @Test
    fun stop_afterStartingOffline_doesNotTouchPeerGroup() {
        whenever(connectionManager.isConnected).thenReturn(false)

        syncManager.start()
        syncManager.stop()

        verify(peerGroup, never()).start()
        verify(peerGroup, never()).stop()
    }

    // --- pre-existing state transitions kept for regression coverage -----

    @Test
    fun onTransactionsFound_setsApiSyncingStateWithCount() {
        syncManager.onTransactionsFound(7)

        val state = syncManager.syncState
        assertTrue(state is BitcoinCore.KitState.ApiSyncing)
        assertEquals(7, (state as BitcoinCore.KitState.ApiSyncing).transactions)
    }

    @Test
    fun onBlockSyncFinished_setsSyncedState() {
        syncManager.onBlockSyncFinished()

        assertEquals(BitcoinCore.KitState.Synced, syncManager.syncState)
    }
}

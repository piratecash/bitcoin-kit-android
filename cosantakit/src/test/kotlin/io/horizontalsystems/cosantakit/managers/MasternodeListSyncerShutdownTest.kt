package io.horizontalsystems.cosantakit.managers

import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.core.IInitialDownload
import io.horizontalsystems.cosantakit.tasks.PeerTaskFactory
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Integration check that MasternodeListSyncer routes work through a
 * [PeerScopedExecutor] with the documented late-callback semantics. One
 * representative test in cosantakit covers all three drop-in copies of this
 * class (piratecashkit and dashkit are symmetric); the executor's own
 * lifecycle is covered in PeerScopedExecutorTest.
 */
class MasternodeListSyncerShutdownTest {

    private lateinit var syncer: MasternodeListSyncer

    @Before
    fun setup() {
        syncer = MasternodeListSyncer(
            mock(BitcoinCore::class.java),
            mock(PeerTaskFactory::class.java),
            mock(MasternodeListManager::class.java),
            mock(IInitialDownload::class.java),
            "TEST"
        )
    }

    @Test
    fun implementsAutoCloseable() {
        // BitcoinCore.stop() / closePeerScopedResources rely on AutoCloseable to
        // tear down per-kit resources even when SharedPeerGroup keeps running.
        assertTrue(syncer is AutoCloseable)
    }

    @Test
    fun onPeerSynced_afterOnStop_doesNotThrow() {
        // Late PeerGroup callback after onStop() must not crash the dispatcher
        // (it would otherwise propagate RejectedExecutionException upward).
        syncer.onStop()
        syncer.onPeerSynced(mock(io.horizontalsystems.bitcoincore.network.peer.Peer::class.java))
    }

    @Test
    fun onPeerSynced_afterClose_doesNotThrow() {
        (syncer as AutoCloseable).close()
        syncer.onPeerSynced(mock(io.horizontalsystems.bitcoincore.network.peer.Peer::class.java))
    }
}

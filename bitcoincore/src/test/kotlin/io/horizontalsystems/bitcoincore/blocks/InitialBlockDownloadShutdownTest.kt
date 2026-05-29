package io.horizontalsystems.bitcoincore.blocks

import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration check that InitialBlockDownload routes peer-scoped work through
 * a [PeerScopedExecutor] with the documented late-callback semantics — the
 * executor's own lifecycle is covered exhaustively in PeerScopedExecutorTest.
 */
class InitialBlockDownloadShutdownTest {

    private lateinit var ibd: InitialBlockDownload

    @Before
    fun setup() {
        ibd = InitialBlockDownload(
            mock<BlockSyncer>(),
            mock<PeerManager>(),
            mock<MerkleBlockExtractor>(),
            "TEST"
        )
    }

    @Test
    fun implementsAutoCloseable() {
        // Required so BitcoinCore.stop() / closePeerScopedResources can tear
        // down this listener even when the surrounding SharedPeerGroup has not
        // dropped its ref count to zero.
        assertTrue(ibd is AutoCloseable)
    }

    @Test
    fun onPeerConnect_afterOnStop_doesNotThrow() {
        // PeerGroup.stop() invokes peerManager.disconnectAll() first and onStop()
        // second, but PeerConnection.run() winds down asynchronously, so a late
        // onPeerConnect callback can fire AFTER onStop() — it unconditionally
        // calls assignNextSyncPeer() → peersQueue.execute(). That must NOT
        // crash the dispatching thread with RejectedExecutionException.
        ibd.onStop()

        val peer = mock<Peer> {
            on { host } doReturn "1.2.3.4"
            on { connectionTime } doReturn 1L
        }
        ibd.onPeerConnect(peer) // fails by propagating RejectedExecutionException
    }

    @Test
    fun onPeerConnect_afterClose_doesNotThrow() {
        // Same observable contract via the explicit close() path used from
        // BitcoinCore.stop().
        (ibd as AutoCloseable).close()

        val peer = mock<Peer> {
            on { host } doReturn "1.2.3.4"
            on { connectionTime } doReturn 1L
        }
        ibd.onPeerConnect(peer)
    }
}

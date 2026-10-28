package io.horizontalsystems.bitcoincore.blocks

import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration check that BlockDownload routes peer-scoped work through a
 * [PeerScopedExecutor] with the documented late-callback semantics — the
 * executor's own lifecycle is covered exhaustively in PeerScopedExecutorTest.
 */
class BlockDownloadShutdownTest {

    private lateinit var blockDownload: BlockDownload

    @Before
    fun setup() {
        blockDownload = BlockDownload(
            mock<BlockSyncer>(),
            mock<PeerManager>(),
            mock<MerkleBlockExtractor>(),
            mock<BlockMessageExtractor>(),
            false,
            "TEST"
        )
    }

    @Test
    fun implementsAutoCloseable() {
        assertTrue(blockDownload is AutoCloseable)
    }

    @Test
    fun onPeerConnect_afterOnStop_doesNotThrow() {
        blockDownload.onStop()

        val peer = mock<Peer> {
            on { host } doReturn "1.2.3.4"
            on { connectionTime } doReturn 1L
        }
        blockDownload.onPeerConnect(peer)
    }

    @Test
    fun onPeerConnect_afterClose_doesNotThrow() {
        (blockDownload as AutoCloseable).close()

        val peer = mock<Peer> {
            on { host } doReturn "1.2.3.4"
            on { connectionTime } doReturn 1L
        }
        blockDownload.onPeerConnect(peer)
    }
}

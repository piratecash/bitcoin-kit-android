package io.horizontalsystems.bitcoincore.blocks

import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.network.peer.task.GetMerkleBlocksTask
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.bitcoincore.network.peer.task.SendTransactionTask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BlockDownloadTest {

    private lateinit var blockSyncer: BlockSyncer
    private lateinit var peerManager: PeerManager
    private lateinit var merkleBlockExtractor: MerkleBlockExtractor
    private lateinit var blockDownload: BlockDownload
    private lateinit var peer: Peer

    @Before
    fun setup() {
        blockSyncer = mock()
        peerManager = mock()
        merkleBlockExtractor = mock()
        blockDownload = BlockDownload(blockSyncer, peerManager, merkleBlockExtractor, false, "TEST")
        peer = mock { on { host } doReturn "1.2.3.4" }
    }

    // Task ownership tests

    @Test
    fun `handleCompletedTask - null owner - handled for GetMerkleBlocksTask`() {
        val task = mock<GetMerkleBlocksTask>()
        whenever(task.owner).thenReturn(null)

        val result = blockDownload.handleCompletedTask(peer, task)
        assertTrue(result)
        verify(blockSyncer).downloadIterationCompleted()
    }

    @Test
    fun `handleCompletedTask - owner is this - handled`() {
        val task = mock<GetMerkleBlocksTask>()
        whenever(task.owner).thenReturn(blockDownload)

        val result = blockDownload.handleCompletedTask(peer, task)
        assertTrue(result)
        verify(blockSyncer).downloadIterationCompleted()
    }

    @Test
    fun `handleCompletedTask - owner is other - rejected`() {
        val otherOwner = Object()
        val task = mock<GetMerkleBlocksTask>()
        whenever(task.owner).thenReturn(otherOwner)

        val result = blockDownload.handleCompletedTask(peer, task)
        assertFalse(result)
        verify(blockSyncer, never()).downloadIterationCompleted()
    }

    @Test
    fun `handleCompletedTask - owner is different BlockDownload - rejected`() {
        val otherBlockDownload = BlockDownload(blockSyncer, peerManager, merkleBlockExtractor, false, "OTHER")
        val task = mock<GetMerkleBlocksTask>()
        whenever(task.owner).thenReturn(otherBlockDownload)

        val result = blockDownload.handleCompletedTask(peer, task)
        assertFalse(result)
    }

    @Test
    fun `handleCompletedTask - unrelated task type - returns false`() {
        val task = mock<SendTransactionTask>()
        whenever(task.owner).thenReturn(null)

        val result = blockDownload.handleCompletedTask(peer, task)
        assertFalse(result)
    }

    // Peer state cleanup

    @Test
    fun `peer disconnect cleans up state`() {
        val peer1 = mock<Peer> { on { host } doReturn "1.1.1.1" }

        val task = mock<GetMerkleBlocksTask>()
        whenever(task.owner).thenReturn(blockDownload)
        blockDownload.handleCompletedTask(peer1, task)

        blockDownload.onPeerDisconnect(peer1, null)

        assertFalse(blockDownload.syncedPeers.contains(peer1))
    }

    @Test
    fun `onRefresh resets all peer sync states`() {
        val peer1 = mock<Peer> {
            on { host } doReturn "1.1.1.1"
            on { connected } doReturn true
        }
        val peer2 = mock<Peer> {
            on { host } doReturn "2.2.2.2"
            on { connected } doReturn true
        }
        whenever(peerManager.connected()).thenReturn(listOf(peer1, peer2))

        blockDownload.onRefresh()

        verify(peer1).synced = false
        verify(peer2).synced = false
    }
}

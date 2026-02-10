package io.horizontalsystems.bitcoincore.blocks

import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.models.InventoryItem
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.network.peer.task.GetBlockHashesTask
import io.horizontalsystems.bitcoincore.network.peer.task.GetMerkleBlocksTask
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.bitcoincore.network.peer.task.SendTransactionTask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InitialBlockDownloadTest {

    private lateinit var blockSyncer: BlockSyncer
    private lateinit var peerManager: PeerManager
    private lateinit var merkleBlockExtractor: MerkleBlockExtractor
    private lateinit var ibd: InitialBlockDownload
    private lateinit var peer: Peer

    @Before
    fun setup() {
        blockSyncer = mock()
        peerManager = mock()
        merkleBlockExtractor = mock()
        ibd = InitialBlockDownload(blockSyncer, peerManager, merkleBlockExtractor, "TEST")
        peer = mock { on { host } doReturn "1.2.3.4" }
    }

    // Task ownership tests

    @Test
    fun `handleCompletedTask - null owner - handled for GetBlockHashesTask`() {
        val task = GetBlockHashesTask(emptyList(), 0)
        task.owner = null

        val result = ibd.handleCompletedTask(peer, task)
        assertTrue(result)
    }

    @Test
    fun `handleCompletedTask - owner is this IBD - handled for GetBlockHashesTask`() {
        val task = GetBlockHashesTask(emptyList(), 0)
        task.owner = ibd

        val result = ibd.handleCompletedTask(peer, task)
        assertTrue(result)
    }

    @Test
    fun `handleCompletedTask - owner is other object - rejected`() {
        val otherOwner = Object()
        val task = GetBlockHashesTask(emptyList(), 0)
        task.owner = otherOwner

        val result = ibd.handleCompletedTask(peer, task)
        assertFalse(result)
    }

    @Test
    fun `handleCompletedTask - owner is different IBD instance - rejected`() {
        val otherIbd = InitialBlockDownload(blockSyncer, peerManager, merkleBlockExtractor, "OTHER")
        val task = GetBlockHashesTask(emptyList(), 0)
        task.owner = otherIbd

        val result = ibd.handleCompletedTask(peer, task)
        assertFalse(result)
    }

    @Test
    fun `handleCompletedTask - GetBlockHashesTask with empty hashes - sets blockHashesSynced`() {
        val task = GetBlockHashesTask(emptyList(), 0)
        task.owner = ibd

        val result = ibd.handleCompletedTask(peer, task)
        assertTrue(result)
        verify(peer).blockHashesSynced = true
    }

    @Test
    fun `handleCompletedTask - GetMerkleBlocksTask owned by this - download iteration completed`() {
        val task = mock<GetMerkleBlocksTask>()
        whenever(task.owner).thenReturn(ibd)

        val result = ibd.handleCompletedTask(peer, task)
        assertTrue(result)
        verify(blockSyncer).downloadIterationCompleted()
    }

    @Test
    fun `handleCompletedTask - unrelated task type - returns false even with null owner`() {
        val task = mock<SendTransactionTask>()
        whenever(task.owner).thenReturn(null)

        val result = ibd.handleCompletedTask(peer, task)
        assertFalse(result)
    }

    // Per-peer sync state isolation

    @Test
    fun `two peers have independent sync state`() {
        val peer1 = mock<Peer> { on { host } doReturn "1.1.1.1" }
        val peer2 = mock<Peer> { on { host } doReturn "2.2.2.2" }

        val task1 = GetBlockHashesTask(emptyList(), 0)
        task1.owner = ibd
        ibd.handleCompletedTask(peer1, task1)

        verify(peer1).blockHashesSynced = true
        verify(peer2, never()).blockHashesSynced = true
    }

    @Test
    fun `peer disconnect cleans up its state`() {
        val peer1 = mock<Peer> { on { host } doReturn "1.1.1.1" }

        val task = GetBlockHashesTask(emptyList(), 0)
        task.owner = ibd
        ibd.handleCompletedTask(peer1, task)

        ibd.onPeerDisconnect(peer1, null)

        assertFalse(ibd.syncedPeers.contains(peer1))
    }

    @Test
    fun `handleInventoryItems does not crash without prior state`() {
        val peer1 = mock<Peer> { on { host } doReturn "1.1.1.1" }
        val items = listOf(InventoryItem(InventoryItem.MSG_BLOCK, ByteArray(32)))

        // Should not throw even without prior state
        ibd.handleInventoryItems(peer1, items)
    }
}

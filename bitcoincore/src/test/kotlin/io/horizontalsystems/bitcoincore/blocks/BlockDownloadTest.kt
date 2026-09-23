package io.horizontalsystems.bitcoincore.blocks

import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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

class BlockDownloadTest {

    private lateinit var blockSyncer: BlockSyncer
    private lateinit var peerManager: PeerManager
    private lateinit var merkleBlockExtractor: MerkleBlockExtractor
    private lateinit var blockMessageExtractor: BlockMessageExtractor
    private lateinit var blockDownload: BlockDownload
    private lateinit var peer: Peer

    @Before
    fun setup() {
        blockSyncer = mock()
        peerManager = mock()
        merkleBlockExtractor = mock()
        blockMessageExtractor = mock()
        blockDownload = BlockDownload(blockSyncer, peerManager, merkleBlockExtractor, blockMessageExtractor, false, "TEST")
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
        val otherBlockDownload = BlockDownload(
            blockSyncer,
            peerManager,
            merkleBlockExtractor,
            blockMessageExtractor,
            false,
            "OTHER"
        )
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

    @Test
    fun `handleCompletedTask - GetBlockHashesTask with hashes - adds block hashes`() {
        val blockHash = ByteArray(32) { 1 }
        val task = GetBlockHashesTask(emptyList(), 1)
        task.owner = blockDownload
        task.blockHashes = listOf(blockHash)

        val result = blockDownload.handleCompletedTask(peer, task)

        assertTrue(result)
        verify(blockSyncer).addBlockHashes(listOf(blockHash))
    }

    @Test
    fun `handleCompletedTask - GetBlockHashesTask with empty hashes - marks block hashes synced`() {
        val task = GetBlockHashesTask(emptyList(), 0)
        task.owner = blockDownload

        val result = blockDownload.handleCompletedTask(peer, task)

        assertTrue(result)
        verify(peer).blockHashesSynced = true
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

    @Test
    fun `handleInventoryItems - request unknown blocks - adds block hashes`() {
        val blockHash1 = ByteArray(32) { 1 }
        val transactionHash = ByteArray(32) { 2 }
        val blockHash2 = ByteArray(32) { 3 }
        blockDownload = BlockDownload(blockSyncer, peerManager, merkleBlockExtractor, blockMessageExtractor, true, "TEST")
        givenSyncedPeer()

        blockDownload.handleInventoryItems(
            peer,
            listOf(
                InventoryItem(InventoryItem.MSG_BLOCK, blockHash1),
                InventoryItem(InventoryItem.MSG_TX, transactionHash),
                InventoryItem(InventoryItem.MSG_BLOCK, blockHash2),
            )
        )

        verify(blockSyncer).addBlockHashes(listOf(blockHash1, blockHash2))
    }

    @Test
    fun `handleInventoryItems - do not request unknown blocks - does not add block hashes`() {
        val blockHash = ByteArray(32) { 1 }
        givenSyncedPeer()

        blockDownload.handleInventoryItems(
            peer,
            listOf(InventoryItem(InventoryItem.MSG_BLOCK, blockHash))
        )

        verify(blockSyncer, never()).addBlockHashes(any())
    }

    @Test
    fun `handleInventoryItems - request unknown blocks from unsynced peer - does not add block hashes`() {
        val blockHash = ByteArray(32) { 1 }
        blockDownload = BlockDownload(blockSyncer, peerManager, merkleBlockExtractor, blockMessageExtractor, true, "TEST")

        blockDownload.handleInventoryItems(
            peer,
            listOf(InventoryItem(InventoryItem.MSG_BLOCK, blockHash))
        )

        verify(blockSyncer, never()).addBlockHashes(any())
    }

    @Test
    fun `onPeerReady - request unknown blocks and peer ahead - requests block hashes before syncing`() {
        val locatorHash = ByteArray(32) { 1 }
        blockDownload = BlockDownload(blockSyncer, peerManager, merkleBlockExtractor, blockMessageExtractor, true, "TEST")
        whenever(peer.ready).thenReturn(true)
        whenever(peer.announcedLastBlockHeight).thenReturn(102)
        whenever(blockSyncer.getOrphanParents()).thenReturn(emptyList())
        whenever(blockSyncer.getBlockHashes(50)).thenReturn(emptyList())
        whenever(blockSyncer.localKnownBestBlockHeight).thenReturn(100)
        whenever(blockSyncer.getBlockLocatorHashes(102)).thenReturn(listOf(locatorHash))
        blockDownload.syncPeer = peer

        blockDownload.onPeerReady(peer)

        verify(peer).addTask(any<GetBlockHashesTask>())
        verify(peer, never()).sendMempoolMessage()
        verify(blockSyncer, never()).downloadCompleted()
    }

    @Test
    fun `onPeerReady - request unknown blocks and peer caught up - completes sync`() {
        blockDownload = BlockDownload(blockSyncer, peerManager, merkleBlockExtractor, blockMessageExtractor, true, "TEST")
        whenever(peer.ready).thenReturn(true)
        whenever(peer.announcedLastBlockHeight).thenReturn(100)
        whenever(peerManager.sorted()).thenReturn(emptyList())
        whenever(blockSyncer.getOrphanParents()).thenReturn(emptyList())
        whenever(blockSyncer.getBlockHashes(50)).thenReturn(emptyList())
        whenever(blockSyncer.localKnownBestBlockHeight).thenReturn(100)
        blockDownload.syncPeer = peer

        blockDownload.onPeerReady(peer)

        verify(peer).blockHashesSynced = true
        verify(peer).synced = true
        verify(blockSyncer).downloadCompleted()
        verify(peer).sendMempoolMessage()
    }

    @Test
    fun `onPeerReady - do not request unknown blocks and empty queue - completes sync`() {
        whenever(peer.ready).thenReturn(true)
        whenever(peerManager.sorted()).thenReturn(emptyList())
        whenever(blockSyncer.getOrphanParents()).thenReturn(emptyList())
        whenever(blockSyncer.getBlockHashes(50)).thenReturn(emptyList())
        blockDownload.syncPeer = peer

        blockDownload.onPeerReady(peer)

        verify(peer).synced = true
        verify(blockSyncer).downloadCompleted()
        verify(peer).sendMempoolMessage()
    }

    private fun givenSyncedPeer() {
        whenever(peer.ready).thenReturn(true)
        whenever(peerManager.sorted()).thenReturn(emptyList())
        whenever(blockSyncer.getOrphanParents()).thenReturn(emptyList())
        whenever(blockSyncer.getBlockHashes(50)).thenReturn(emptyList())
        blockDownload.syncPeer = peer
        blockDownload.onPeerReady(peer)
    }
}

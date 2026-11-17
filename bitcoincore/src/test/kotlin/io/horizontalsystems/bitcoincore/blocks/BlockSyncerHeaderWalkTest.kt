package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.core.IPublicKeyManager
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.BlockHash
import io.horizontalsystems.bitcoincore.models.Checkpoint
import io.horizontalsystems.bitcoincore.models.MerkleBlock
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.horizontalsystems.bitcoincore.transactions.BlockTransactionProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BlockSyncerHeaderWalkTest {

    private val storage = mock<IStorage>()
    private val blockchain = mock<Blockchain>()
    private val transactionProcessor = mock<BlockTransactionProcessor>()
    private val publicKeyManager = mock<IPublicKeyManager>()
    private val checkpoint = mock<Checkpoint>()

    private lateinit var blockSyncer: BlockSyncer

    @Before
    fun setup() {
        whenever(storage.getBlockHashesSortedBySequenceAndHeight(any())).thenReturn(listOf(BlockHash(OTHER_HASH, 0)))
        whenever(checkpoint.block).thenReturn(block(CHECKPOINT_HEIGHT, CHECKPOINT_HASH, byteArrayOf()))
        whenever(storage.getBlock(PARENT_HASH)).thenReturn(block(END_HEIGHT, PARENT_HASH, GRANDPARENT_HASH))
        whenever(blockchain.connect(any())).thenThrow(BlockValidatorException.NoCheckpointBlock(TARGET_HEIGHT))

        blockSyncer = BlockSyncer(
            storage,
            blockchain,
            transactionProcessor,
            publicKeyManager,
            checkpoint,
            BlockSyncer.State(),
            headerWalkEnabled = true
        )
    }

    @Test
    fun `handleMerkleBlock - walk is enabled - asks for headers instead of queuing one ancestor`() {
        startWalk()

        assertArrayEquals(CHECKPOINT_HASH, blockSyncer.pendingHeaderLocator()?.single())
        assertEquals(listOf(OTHER_HASH.toList()), blockSyncer.getBlockHashes(10).map { it.headerHash.toList() })
    }

    @Test
    fun `handleBlockHeaders - segment resolves - writes the endpoint first and the target last`() {
        startWalk()

        blockSyncer.handleBlockHeaders(segment())

        inOrder(blockchain) {
            verify(blockchain).insertVerifiedAncestor(any(), eq(END_HEIGHT))
            verify(blockchain).insertVerifiedAncestor(any(), eq(END_HEIGHT - 1))
            verify(blockchain).insertVerifiedAncestor(any(), eq(TARGET_HEIGHT))
        }
    }

    @Test
    fun `handleBlockHeaders - segment resolves - the walk is finished and nothing is queued`() {
        startWalk()

        blockSyncer.handleBlockHeaders(segment())

        assertNull(blockSyncer.pendingHeaderLocator())
        assertEquals(listOf(OTHER_HASH.toList()), blockSyncer.getBlockHashes(10).map { it.headerHash.toList() })
    }

    @Test
    fun `handleBlockHeaders - walk aborts - falls back to the descent with the retained failing block`() {
        startWalk()

        blockSyncer.handleBlockHeaders(emptyArray())

        assertNull(blockSyncer.pendingHeaderLocator())
        val queued = blockSyncer.getBlockHashes(10).first()
        assertArrayEquals(GRANDPARENT_HASH, queued.headerHash)
        assertEquals(END_HEIGHT - 1, queued.height)
    }

    @Test
    fun `handleBlockHeaders - download context was reset - the late result writes nothing`() {
        startWalk()
        blockSyncer.prepareForDownload()

        blockSyncer.handleBlockHeaders(segment())

        verify(blockchain, never()).insertVerifiedAncestor(any(), any())
    }

    @Test
    fun `handleBlockHeaders - endpoint was deleted before publication - the result is dropped`() {
        startWalk()
        whenever(storage.getBlock(PARENT_HASH)).thenReturn(null)

        blockSyncer.handleBlockHeaders(segment())

        verify(blockchain, never()).insertVerifiedAncestor(any(), any())
    }

    @Test
    fun `handleBlockHeaders - endpoint moved to another height - the result is dropped`() {
        startWalk()
        whenever(storage.getBlock(PARENT_HASH)).thenReturn(block(END_HEIGHT + 1, PARENT_HASH, GRANDPARENT_HASH))

        blockSyncer.handleBlockHeaders(segment())

        verify(blockchain, never()).insertVerifiedAncestor(any(), any())
    }

    @Test
    fun `handleBlockHeaders - publication fails - the exception reaches the caller so the peer is dropped`() {
        startWalk()
        whenever(blockchain.insertVerifiedAncestor(any(), any())).thenThrow(RuntimeException("db is gone"))

        assertThrows(RuntimeException::class.java) { blockSyncer.handleBlockHeaders(segment()) }
    }

    @Test
    fun `handleMerkleBlock - the failing block retries while a walk runs - the walk keeps its progress`() {
        startWalk()
        blockSyncer.handleBlockHeaders(arrayOf(segment().first()))
        val advanced = blockSyncer.pendingHeaderLocator()?.single()

        assertThrows(BlockValidatorException.AncestorDownloadQueued::class.java) {
            blockSyncer.handleMerkleBlock(failingBlock(), MAX_BLOCK_HEIGHT)
        }

        assertArrayEquals(advanced, blockSyncer.pendingHeaderLocator()?.single())
        assertEquals(listOf(OTHER_HASH.toList()), blockSyncer.getBlockHashes(10).map { it.headerHash.toList() })
    }

    @Test
    fun `handleBlockHeaders - the same resolved segment arrives twice - only the first publishes`() {
        startWalk()
        val headers = segment()

        blockSyncer.handleBlockHeaders(headers)
        blockSyncer.handleBlockHeaders(headers)

        verify(blockchain, times(END_HEIGHT - TARGET_HEIGHT + 1)).insertVerifiedAncestor(any(), any())
    }

    @Test
    fun `handleBlockHeaders - the context is reset while the segment is accepted - nothing is published`() {
        startWalk()
        val headers = segment()
        headers[headers.lastIndex] = resettingHeader(headers.last())

        blockSyncer.handleBlockHeaders(headers)

        verify(blockchain, never()).insertVerifiedAncestor(any(), any())
    }

    @Test
    fun `handleBlockHeaders - publication dies after the endpoint - the target is left absent for the retry`() {
        startWalk()
        whenever(blockchain.insertVerifiedAncestor(any(), eq(END_HEIGHT - 1))).thenThrow(RuntimeException("db is gone"))

        assertThrows(RuntimeException::class.java) { blockSyncer.handleBlockHeaders(segment()) }

        verify(blockchain).insertVerifiedAncestor(any(), eq(END_HEIGHT))
        verify(blockchain, never()).insertVerifiedAncestor(any(), eq(TARGET_HEIGHT))
    }

    @Test
    fun `handleMerkleBlock - the walk already aborted - the retry descends instead of walking again`() {
        startWalk()
        blockSyncer.handleBlockHeaders(emptyArray())

        assertThrows(BlockValidatorException.AncestorDownloadQueued::class.java) {
            blockSyncer.handleMerkleBlock(failingBlock(), MAX_BLOCK_HEIGHT)
        }

        assertNull(blockSyncer.pendingHeaderLocator())
    }

    @Test
    fun `handleMerkleBlock - a new download context after an abort - the walk is available again`() {
        startWalk()
        blockSyncer.handleBlockHeaders(emptyArray())
        blockSyncer.prepareForDownload()

        startWalk()
    }

    @Test
    fun `handleBlockHeaders - the endpoint is deleted midway - the remaining ancestors are not written`() {
        startWalk()
        whenever(blockchain.insertVerifiedAncestor(any(), eq(END_HEIGHT))).doAnswer {
            whenever(storage.getBlock(PARENT_HASH)).thenReturn(null)
            block(END_HEIGHT, PARENT_HASH, GRANDPARENT_HASH)
        }

        blockSyncer.handleBlockHeaders(segment())

        verify(blockchain).insertVerifiedAncestor(any(), eq(END_HEIGHT))
        verify(blockchain, never()).insertVerifiedAncestor(any(), eq(TARGET_HEIGHT))
    }

    @Test
    fun `handleMerkleBlock - no stored ancestor - no walk starts and the original failure propagates`() {
        whenever(storage.getBlock(PARENT_HASH)).thenReturn(null)

        assertThrows(BlockValidatorException.NoCheckpointBlock::class.java) {
            blockSyncer.handleMerkleBlock(failingBlock(), MAX_BLOCK_HEIGHT)
        }

        assertNull(blockSyncer.pendingHeaderLocator())
    }

    private fun startWalk() {
        assertThrows(BlockValidatorException.AncestorDownloadQueued::class.java) {
            blockSyncer.handleMerkleBlock(failingBlock(), MAX_BLOCK_HEIGHT)
        }
        assertNotNull(blockSyncer.pendingHeaderLocator())
    }

    /** Headers linking the checkpoint to the stored endpoint, covering the whole gap. */
    private fun segment(): Array<BlockHeader> {
        var previous = CHECKPOINT_HASH
        return Array(END_HEIGHT - CHECKPOINT_HEIGHT) { index ->
            val height = CHECKPOINT_HEIGHT + 1 + index
            val hash = if (height == END_HEIGHT) PARENT_HASH else byteArrayOf(height.toByte())
            header(hash, previous).also { previous = hash }
        }
    }

    /** Resets the download context while accept() reads this header, i.e. after the walk was captured. */
    private fun resettingHeader(original: BlockHeader): BlockHeader = mock {
        on { previousBlockHeaderHash } doReturn original.previousBlockHeaderHash
        on { hash } doAnswer {
            blockSyncer.prepareForDownload()
            original.hash
        }
    }

    private fun failingBlock() = MerkleBlock(header(CHILD_HASH, PARENT_HASH), emptyMap())

    private fun header(hash: ByteArray, previousHash: ByteArray) = BlockHeader(
        version = 1,
        previousBlockHeaderHash = previousHash,
        merkleRoot = byteArrayOf(9),
        timestamp = 0,
        bits = 0,
        nonce = 0,
        hash = hash
    )

    private fun block(height: Int, headerHash: ByteArray, previousBlockHash: ByteArray) = Block().apply {
        this.height = height
        this.headerHash = headerHash
        this.previousBlockHash = previousBlockHash
    }

    private companion object {
        const val CHECKPOINT_HEIGHT = 100
        const val TARGET_HEIGHT = 102
        const val END_HEIGHT = 104
        const val MAX_BLOCK_HEIGHT = 200
        val CHILD_HASH = byteArrayOf(1)
        val PARENT_HASH = byteArrayOf(2)
        val GRANDPARENT_HASH = byteArrayOf(3)
        val OTHER_HASH = byteArrayOf(5)
        val CHECKPOINT_HASH = byteArrayOf(6)
    }
}

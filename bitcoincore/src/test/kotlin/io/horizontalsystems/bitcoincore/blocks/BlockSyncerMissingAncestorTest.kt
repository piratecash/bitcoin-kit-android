package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.core.IBlockSyncListener
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
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BlockSyncerMissingAncestorTest {

    private val storage = mock<IStorage>()
    private val blockchain = mock<Blockchain>()
    private val transactionProcessor = mock<BlockTransactionProcessor>()
    private val publicKeyManager = mock<IPublicKeyManager>()
    private val checkpoint = mock<Checkpoint>()
    private val listener = mock<IBlockSyncListener>()

    private lateinit var blockSyncer: BlockSyncer

    @Before
    fun setup() {
        whenever(storage.getBlockHashesSortedBySequenceAndHeight(any())).thenReturn(listOf(BlockHash(OTHER_HASH, 0)))
        whenever(checkpoint.block).thenReturn(block(CHECKPOINT_HEIGHT, CHECKPOINT_HASH, byteArrayOf()))
        blockSyncer = BlockSyncer(
            storage,
            blockchain,
            transactionProcessor,
            publicKeyManager,
            checkpoint,
            BlockSyncer.State()
        )
        blockSyncer.listener = listener
    }

    @Test
    fun `handleMerkleBlock - parent link is broken - queues the parent of the last stored ancestor`() {
        givenStoredParent(previousBlockHash = GRANDPARENT_HASH)

        val exception = assertThrows(BlockValidatorException.AncestorDownloadQueued::class.java) {
            blockSyncer.handleMerkleBlock(failingBlock(), MAX_BLOCK_HEIGHT)
        }

        assertEquals(MISSING_HEIGHT, exception.height)
        val queued = blockSyncer.getBlockHashes(10).first()
        assertArrayEquals(GRANDPARENT_HASH, queued.headerHash)
        assertEquals(BOUNDARY_HEIGHT - 2, queued.height)
    }

    @Test
    fun `getBlockHashes - pending ancestor is requested ahead of the stored hashes`() {
        givenStoredParent(previousBlockHash = GRANDPARENT_HASH)
        connectFailing()

        val hashes = blockSyncer.getBlockHashes(10)

        assertEquals(2, hashes.size)
        assertArrayEquals(GRANDPARENT_HASH, hashes[0].headerHash)
        assertArrayEquals(OTHER_HASH, hashes[1].headerHash)
    }

    @Test
    fun `handleMerkleBlock - pending ancestor arrives - stops being requested and is not reported as sync progress`() {
        givenStoredParent(previousBlockHash = GRANDPARENT_HASH)
        connectFailing()
        whenever(blockchain.forceAdd(any(), any())).thenReturn(block(BOUNDARY_HEIGHT - 2, GRANDPARENT_HASH, byteArrayOf(7)))

        blockSyncer.handleMerkleBlock(merkleBlock(GRANDPARENT_HASH, height = BOUNDARY_HEIGHT - 2), MAX_BLOCK_HEIGHT)

        assertEquals(listOf(OTHER_HASH.toList()), blockSyncer.getBlockHashes(10).map { it.headerHash.toList() })
        verify(listener, never()).onBlockForceAdded()
    }

    @Test
    fun `handleMerkleBlock - ordinary api block is force added - is still reported as sync progress`() {
        whenever(blockchain.forceAdd(any(), any())).thenReturn(block(BOUNDARY_HEIGHT, CHILD_HASH, PARENT_HASH))

        blockSyncer.handleMerkleBlock(merkleBlock(CHILD_HASH, height = BOUNDARY_HEIGHT), MAX_BLOCK_HEIGHT)

        verify(listener).onBlockForceAdded()
    }

    @Test
    fun `handleMerkleBlock - parent is not stored - original failure propagates and nothing is queued`() {
        whenever(storage.getBlock(PARENT_HASH)).thenReturn(null)
        whenever(blockchain.connect(any())).thenThrow(BlockValidatorException.NoCheckpointBlock(MISSING_HEIGHT))

        assertThrows(BlockValidatorException.NoCheckpointBlock::class.java) {
            blockSyncer.handleMerkleBlock(failingBlock(), MAX_BLOCK_HEIGHT)
        }

        assertEquals(1, blockSyncer.getBlockHashes(10).size)
    }

    @Test
    fun `handleMerkleBlock - stored ancestor is an api placeholder - requests the placeholder itself`() {
        givenStoredParent(previousBlockHash = byteArrayOf())

        assertThrows(BlockValidatorException.AncestorDownloadQueued::class.java) {
            blockSyncer.handleMerkleBlock(failingBlock(), MAX_BLOCK_HEIGHT)
        }

        val queued = blockSyncer.getBlockHashes(10).first()
        assertArrayEquals(PARENT_HASH, queued.headerHash)
        assertEquals(BOUNDARY_HEIGHT - 1, queued.height)
    }

    @Test
    fun `handleMerkleBlock - ancestors already stored - descends past them to the first broken link`() {
        givenStoredParent(previousBlockHash = GRANDPARENT_HASH)
        whenever(storage.getBlock(GRANDPARENT_HASH))
            .thenReturn(block(BOUNDARY_HEIGHT - 2, GRANDPARENT_HASH, GREAT_GRANDPARENT_HASH))

        connectFailing()

        val queued = blockSyncer.getBlockHashes(10).first()
        assertArrayEquals(GREAT_GRANDPARENT_HASH, queued.headerHash)
        assertEquals(BOUNDARY_HEIGHT - 3, queued.height)
    }

    @Test
    fun `handleMerkleBlock - recovery resolves ancestors by header hash only, never by height`() {
        givenStoredParent(previousBlockHash = GRANDPARENT_HASH)

        connectFailing()

        val queued = blockSyncer.getBlockHashes(10).first()
        assertArrayEquals(GRANDPARENT_HASH, queued.headerHash)
        verify(storage, never()).getBlock(any<Int>())
        verify(storage, never()).getBlockByHeightStalePrioritized(any())
        verify(storage, never()).getBlocks(any(), any(), any())
        verify(storage, never()).getBlocks(any<Int>(), any<Boolean>())
    }

    @Test
    fun `prepareForDownload - pending recovery is discarded together with the download context`() {
        givenStoredParent(previousBlockHash = GRANDPARENT_HASH)
        connectFailing()

        blockSyncer.prepareForDownload()

        assertEquals(listOf(OTHER_HASH.toList()), blockSyncer.getBlockHashes(10).map { it.headerHash.toList() })
    }

    private fun givenStoredParent(previousBlockHash: ByteArray) {
        whenever(storage.getBlock(PARENT_HASH)).thenReturn(block(BOUNDARY_HEIGHT - 1, PARENT_HASH, previousBlockHash))
        whenever(blockchain.connect(any())).thenThrow(BlockValidatorException.NoCheckpointBlock(MISSING_HEIGHT))
    }

    private fun connectFailing() {
        assertThrows(BlockValidatorException.AncestorDownloadQueued::class.java) {
            blockSyncer.handleMerkleBlock(failingBlock(), MAX_BLOCK_HEIGHT)
        }
    }

    private fun failingBlock() = merkleBlock(CHILD_HASH)

    private fun block(height: Int, headerHash: ByteArray, previousBlockHash: ByteArray) = Block().apply {
        this.height = height
        this.headerHash = headerHash
        this.previousBlockHash = previousBlockHash
    }

    private fun merkleBlock(hash: ByteArray, height: Int? = null): MerkleBlock {
        val header = BlockHeader(
            version = 1,
            previousBlockHeaderHash = PARENT_HASH,
            merkleRoot = byteArrayOf(9),
            timestamp = 0,
            bits = 0,
            nonce = 0,
            hash = hash
        )
        return MerkleBlock(header, emptyMap()).apply { this.height = height }
    }

    private companion object {
        const val BOUNDARY_HEIGHT = 3106656
        const val MISSING_HEIGHT = 3104639
        const val MAX_BLOCK_HEIGHT = 3106700
        val CHILD_HASH = byteArrayOf(1)
        val PARENT_HASH = byteArrayOf(2)
        val GRANDPARENT_HASH = byteArrayOf(3)
        val GREAT_GRANDPARENT_HASH = byteArrayOf(4)
        val OTHER_HASH = byteArrayOf(5)
        const val CHECKPOINT_HEIGHT = 3000000
        val CHECKPOINT_HASH = byteArrayOf(6)
    }
}

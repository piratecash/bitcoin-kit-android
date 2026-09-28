package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.MerkleBlock
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BlockchainForceAddRefreshTest {

    private val storage = mock<IStorage>()
    private val dataListener = mock<IBlockchainDataListener>()
    private val blockchain = Blockchain(storage, null, dataListener, "TEST")

    @Test
    fun `forceAdd - stored row is an api placeholder - is refreshed from the received header`() {
        val stored = placeholderBlock()
        whenever(storage.getBlock(HEADER_HASH)).thenReturn(stored)

        val block = blockchain.forceAdd(merkleBlock(), HEIGHT)

        verify(storage).updateBlock(stored)
        assertArrayEquals(PREVIOUS_HASH, block.previousBlockHash)
        assertArrayEquals(MERKLE_ROOT, block.merkleRoot)
        assertEquals(TIMESTAMP, block.timestamp)
        assertEquals(BITS, block.bits)
        assertEquals(NONCE, block.nonce)
        assertEquals(HEIGHT, block.height)
    }

    @Test
    fun `forceAdd - stored row already matches the header - is not written again`() {
        whenever(storage.getBlock(HEADER_HASH)).thenReturn(completeBlock())

        blockchain.forceAdd(merkleBlock(), HEIGHT)

        verify(storage, never()).updateBlock(any())
    }

    @Test
    fun `connect - stored row is an api placeholder - keeps refreshing it as before`() {
        val stored = placeholderBlock()
        whenever(storage.getBlock(HEADER_HASH)).thenReturn(stored)

        val block = blockchain.connect(merkleBlock())

        verify(storage).updateBlock(stored)
        assertArrayEquals(PREVIOUS_HASH, block.previousBlockHash)
        assertEquals(BITS, block.bits)
    }

    @Test
    fun `insertVerifiedAncestor - stored row is an api placeholder - is repaired from the proven header`() {
        val stored = placeholderBlock()
        whenever(storage.getBlock(HEADER_HASH)).thenReturn(stored)

        val block = blockchain.insertVerifiedAncestor(merkleBlock().header, HEIGHT)

        verify(storage).updateBlock(stored)
        assertEquals(BITS, block.bits)
        assertArrayEquals(PREVIOUS_HASH, block.previousBlockHash)
    }

    @Test
    fun `insertVerifiedAncestor - no stored row - inserts the header at the proven height`() {
        whenever(storage.getBlock(HEADER_HASH)).thenReturn(null)

        val block = blockchain.insertVerifiedAncestor(merkleBlock().header, HEIGHT)

        verify(storage).addBlock(block)
        assertEquals(HEIGHT, block.height)
    }

    @Test
    fun `insertLastBlock - a complete row already exists - is never overwritten by an api placeholder`() {
        val stored = completeBlock()
        whenever(storage.getBlock(HEADER_HASH)).thenReturn(stored)

        blockchain.insertLastBlock(placeholderHeader(), HEIGHT)

        verify(storage, never()).updateBlock(any())
        verify(storage, never()).addBlock(any())
    }

    private fun placeholderHeader() = BlockHeader(
        version = 0,
        previousBlockHeaderHash = byteArrayOf(),
        merkleRoot = byteArrayOf(),
        timestamp = TIMESTAMP,
        bits = -1,
        nonce = 0,
        hash = HEADER_HASH
    )

    private fun placeholderBlock() = Block().apply {
        height = HEIGHT
        headerHash = HEADER_HASH
        previousBlockHash = byteArrayOf()
        merkleRoot = byteArrayOf()
        bits = -1
    }

    private fun completeBlock() = Block().apply {
        height = HEIGHT
        headerHash = HEADER_HASH
        previousBlockHash = PREVIOUS_HASH
        merkleRoot = MERKLE_ROOT
        version = VERSION
        timestamp = TIMESTAMP
        bits = BITS
        nonce = NONCE
    }

    private fun merkleBlock(): MerkleBlock {
        val header = BlockHeader(
            version = VERSION,
            previousBlockHeaderHash = PREVIOUS_HASH,
            merkleRoot = MERKLE_ROOT,
            timestamp = TIMESTAMP,
            bits = BITS,
            nonce = NONCE,
            hash = HEADER_HASH
        )
        return MerkleBlock(header, emptyMap())
    }

    private companion object {
        const val HEIGHT = 3104639
        const val VERSION = 1
        const val TIMESTAMP = 1_600_000_000L
        const val BITS = 0x1a0fffffL
        const val NONCE = 42L
        val HEADER_HASH = byteArrayOf(1)
        val PREVIOUS_HASH = byteArrayOf(2)
        val MERKLE_ROOT = byteArrayOf(3)
    }
}

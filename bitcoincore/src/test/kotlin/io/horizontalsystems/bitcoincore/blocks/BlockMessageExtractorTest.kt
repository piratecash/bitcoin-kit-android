package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.core.HashBytes
import io.horizontalsystems.bitcoincore.message.BlockMessageTestData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BlockMessageExtractorTest {

    private val extractor = BlockMessageExtractor(maxBlockSize = 32 * 1024 * 1024)

    @Test
    fun extract_validFullBlock_returnsCompleteMerkleBlock() {
        val transaction = BlockMessageTestData.transaction()
        val message = BlockMessageTestData.blockMessage(transactions = listOf(transaction))

        val merkleBlock = extractor.extract(message)

        assertArrayEquals(message.header.hash, merkleBlock.blockHash)
        assertEquals(mapOf(HashBytes(transaction.header.hash) to true), merkleBlock.associatedTransactionHashes)
        assertSame(transaction, merkleBlock.associatedTransactions.single())
    }

    @Test(expected = InvalidMerkleBlockException::class)
    fun extract_invalidMerkleRoot_throws() {
        val message = BlockMessageTestData.blockMessage(
            header = BlockMessageTestData.header(merkleRoot = ByteArray(32) { 9 })
        )

        extractor.extract(message)
    }
}

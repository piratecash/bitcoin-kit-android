package io.horizontalsystems.bitcoincore.network.peer.task

import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import io.horizontalsystems.bitcoincore.blocks.BlockMessageExtractor
import io.horizontalsystems.bitcoincore.blocks.MerkleBlockExtractor
import io.horizontalsystems.bitcoincore.message.BlockMessageTestData
import io.horizontalsystems.bitcoincore.models.BlockHash
import io.horizontalsystems.bitcoincore.models.MerkleBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GetMerkleBlocksTaskBlockMessageTest {

    private val handler = CapturingHandler()
    private val listener = mock<PeerTask.Listener>()

    @Test
    fun handleMessage_requestedFullBlock_processesAndCompletesTask() {
        val message = BlockMessageTestData.blockMessage()
        val task = taskFor(listOf(BlockHash(message.header.hash, height = 123)))
        task.listener = listener

        val handled = task.handleMessage(message)

        assertTrue(handled)
        assertEquals(123, handler.blocks.single().height)
        assertSame(message.transactions.single(), handler.blocks.single().associatedTransactions.single())
        verify(listener).onTaskCompleted(task)
    }

    @Test
    fun handleMessage_unrequestedFullBlock_returnsFalse() {
        val message = BlockMessageTestData.blockMessage()
        val task = taskFor(listOf(BlockHash(ByteArray(32) { 1 }, height = 123)))
        task.listener = listener

        val handled = task.handleMessage(message)

        assertFalse(handled)
        assertTrue(handler.blocks.isEmpty())
        verifyNoMoreInteractions(listener)
    }

    private fun taskFor(hashes: List<BlockHash>): GetMerkleBlocksTask {
        return GetMerkleBlocksTask(
            hashes = hashes,
            merkleBlockHandler = handler,
            merkleBlockExtractor = mock<MerkleBlockExtractor>(),
            blockMessageExtractor = BlockMessageExtractor(maxBlockSize = 32 * 1024 * 1024),
            minMerkleBlocks = 500.0,
            minTransactions = 50_000.0,
            minReceiveBytes = 100_000.0,
            logTag = "TEST"
        )
    }

    private class CapturingHandler : GetMerkleBlocksTask.MerkleBlockHandler {
        val blocks = mutableListOf<MerkleBlock>()

        override fun handleMerkleBlock(merkleBlock: MerkleBlock) {
            blocks.add(merkleBlock)
        }
    }
}

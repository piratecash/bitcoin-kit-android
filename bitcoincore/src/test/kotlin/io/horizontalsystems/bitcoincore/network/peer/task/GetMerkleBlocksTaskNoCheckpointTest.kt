package io.horizontalsystems.bitcoincore.network.peer.task

import io.horizontalsystems.bitcoincore.blocks.BlockMessageExtractor
import io.horizontalsystems.bitcoincore.blocks.MerkleBlockExtractor
import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.models.BlockHash
import io.horizontalsystems.bitcoincore.models.MerkleBlock
import io.horizontalsystems.bitcoincore.network.messages.MerkleBlockMessage
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GetMerkleBlocksTaskNoCheckpointTest {

    private val merkleBlockHandler = mock<GetMerkleBlocksTask.MerkleBlockHandler>()
    private val merkleBlockExtractor = mock<MerkleBlockExtractor>()
    private val blockMessageExtractor = mock<BlockMessageExtractor>()
    private val listener = mock<PeerTask.Listener>()
    private val message = mock<MerkleBlockMessage>()

    private lateinit var task: GetMerkleBlocksTask

    @Before
    fun setup() {
        task = GetMerkleBlocksTask(
            hashes = listOf(BlockHash(HEADER_HASH, 0)),
            merkleBlockHandler = merkleBlockHandler,
            merkleBlockExtractor = merkleBlockExtractor,
            blockMessageExtractor = blockMessageExtractor,
            minMerkleBlocks = 0.0,
            minTransactions = 0.0,
            minReceiveBytes = 0.0,
            logTag = "TEST"
        )
        task.listener = listener
        whenever(message.txCount).thenReturn(0)
        whenever(merkleBlockExtractor.extract(any())).thenReturn(merkleBlock())
    }

    @Test
    fun `handleMessage - ancestor download queued - peer is kept and the task completes`() {
        doThrow(BlockValidatorException.AncestorDownloadQueued(MISSING_HEIGHT))
            .whenever(merkleBlockHandler).handleMerkleBlock(any())

        task.handleMessage(message)

        verify(listener, never()).onTaskFailed(any(), any())
        verify(listener).onTaskCompleted(task)
    }

    @Test
    fun `handleMessage - unrecoverable missing checkpoint - peer is dropped`() {
        doThrow(BlockValidatorException.NoCheckpointBlock(MISSING_HEIGHT))
            .whenever(merkleBlockHandler).handleMerkleBlock(any())

        task.handleMessage(message)

        verify(listener).onTaskFailed(any(), any())
    }

    @Test
    fun `handleMessage - any other failure - peer is dropped`() {
        doThrow(IllegalStateException("boom"))
            .whenever(merkleBlockHandler).handleMerkleBlock(any())

        task.handleMessage(message)

        verify(listener).onTaskFailed(any(), any())
    }

    private fun merkleBlock(): MerkleBlock {
        val header = BlockHeader(
            version = 1,
            previousBlockHeaderHash = byteArrayOf(2),
            merkleRoot = byteArrayOf(3),
            timestamp = 0,
            bits = 0,
            nonce = 0,
            hash = HEADER_HASH
        )
        return MerkleBlock(header, emptyMap())
    }

    private companion object {
        const val MISSING_HEIGHT = 3104639
        val HEADER_HASH = byteArrayOf(1)
    }
}

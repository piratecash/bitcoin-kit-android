package io.horizontalsystems.bitcoincore.transactions

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.blocks.IBlockchainDataListener
import io.horizontalsystems.bitcoincore.core.IPublicKeyManager
import io.horizontalsystems.bitcoincore.core.ITransactionInfoConverter
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.managers.IIrregularOutputFinder
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionMetadata
import io.horizontalsystems.bitcoincore.rbf.ReplacementTransactionBuilder
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.horizontalsystems.bitcoincore.storage.CoreDatabase
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.Storage
import io.horizontalsystems.bitcoincore.transactions.extractors.TransactionExtractor
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class ReplacementProcessorsTest {
    private lateinit var database: CoreDatabase
    private lateinit var storage: Storage
    private lateinit var resolver: TransactionConflictsResolver

    private val original = byteArrayOf(1)
    private val replacement = byteArrayOf(2)
    private val winner = byteArrayOf(3)
    private val sharedWithOriginal = byteArrayOf(7)
    private val sharedWithWinner = byteArrayOf(8)

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CoreDatabase::class.java
        )
            .setTransactionExecutor(Executors.newSingleThreadExecutor())
            .setQueryExecutor(Executors.newSingleThreadExecutor())
            .allowMainThreadQueries()
            .build()
        storage = Storage(database).apply { setTransactionSerializer(BaseTransactionSerializer()) }
        resolver = TransactionConflictsResolver(storage)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun processCreated_outpointSpentByMined_throwsAndStoresNothing() {
        insertBlock()
        storage.addTransaction(fullTransaction(original, listOf(sharedWithOriginal), blockHash = BLOCK))

        assertThrows(ReplacementTransactionBuilder.BuildError.InvalidTransaction::class.java) {
            pendingProcessor().processCreated(fullTransaction(replacement, listOf(sharedWithOriginal), sequence = 1))
        }

        assertNull(storage.getTransaction(replacement))
    }

    @Test
    fun processReceivedPending_winnerBeatsLoser_originalLinkMovesToWinner() {
        insertOriginalBeatenByReplacement()

        pendingProcessor().processReceived(listOf(fullTransaction(winner, listOf(sharedWithWinner), sequence = 1)), true)

        assertArrayEquals(winner, storage.getTransaction(original)?.conflictingTxHash)
    }

    @Test
    fun processReceivedBlock_foreignCancellation_originalKeepsLink() {
        storage.addTransaction(fullTransaction(original, listOf(sharedWithOriginal), conflictingTxHash = replacement, isOutgoing = false))
        storage.addTransaction(fullTransaction(replacement, listOf(sharedWithOriginal), isOutgoing = false))
        val block = insertBlock()
        val cancellation = fullTransaction(winner, listOf(sharedWithOriginal), isMine = false)

        assertThrows(BloomFilterManager.BloomFilterExpired::class.java) {
            blockProcessor().processReceived(listOf(cancellation), block, true)
        }

        assertNull(storage.getTransaction(replacement))
        assertArrayEquals(winner, storage.getTransaction(original)?.conflictingTxHash)
    }

    @Test
    fun processReceivedBlock_winnerBeatsLoser_originalLinkMovesToWinner() {
        insertOriginalBeatenByReplacement()
        val block = insertBlock()

        blockProcessor().processReceived(listOf(fullTransaction(winner, listOf(sharedWithWinner), sequence = 1)), block, true)

        assertNull(storage.getTransaction(replacement))
        assertArrayEquals(winner, storage.getTransaction(original)?.conflictingTxHash)
    }

    private fun insertOriginalBeatenByReplacement() {
        storage.addTransaction(fullTransaction(original, listOf(sharedWithOriginal), conflictingTxHash = replacement))
        storage.addTransaction(fullTransaction(replacement, listOf(sharedWithOriginal, sharedWithWinner)))
    }

    private fun insertBlock(): Block {
        val block = Block(BlockHeader(1, byteArrayOf(), byteArrayOf(), 1, 1, 1, BLOCK), 1)
        database.block.insert(block)
        return block
    }

    private fun pendingProcessor() = PendingTransactionProcessor(
        storage,
        mock<TransactionExtractor>(),
        mock<IPublicKeyManager>(),
        mock<IIrregularOutputFinder>(),
        mock<IBlockchainDataListener>(),
        resolver,
        false
    )

    private fun blockProcessor(): BlockTransactionProcessor {
        val info = mock<TransactionInfo> { on { serialize() } doReturn "" }
        val converter = mock<ITransactionInfoConverter> { on { transactionInfo(any()) } doReturn info }
        val listener = mock<IBlockchainDataListener>()
        return BlockTransactionProcessor(
            storage,
            mock<TransactionExtractor>(),
            mock<IPublicKeyManager>(),
            mock<IIrregularOutputFinder>(),
            listener,
            resolver,
            TransactionInvalidator(storage, converter, listener)
        )
    }

    private fun fullTransaction(
        hash: ByteArray,
        spent: List<ByteArray>,
        sequence: Long = 0,
        blockHash: ByteArray? = null,
        conflictingTxHash: ByteArray? = null,
        isMine: Boolean = true,
        isOutgoing: Boolean = true,
    ): FullTransaction {
        val header = Transaction().apply {
            this.hash = hash
            this.blockHash = blockHash
            this.conflictingTxHash = conflictingTxHash
            status = Transaction.Status.RELAYED
            timestamp = 100
            this.isMine = isMine
            this.isOutgoing = isOutgoing
        }
        val inputs = spent.map { previous ->
            TransactionInput(previous, 0, sequence = sequence).apply { transactionHash = hash }
        }
        return FullTransaction(header, inputs, listOf(), forceHashUpdate = false).apply {
            metadata = TransactionMetadata(hash)
        }
    }

    private companion object {
        val BLOCK = byteArrayOf(9)
    }
}

package io.horizontalsystems.bitcoincore.transactions

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.horizontalsystems.bitcoincore.storage.CoreDatabase
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.Storage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class TransactionConflictsResolverStorageTest {
    private lateinit var database: CoreDatabase
    private lateinit var resolver: TransactionConflictsResolver

    private val a = byteArrayOf(1)
    private val b = byteArrayOf(2)
    private val c = byteArrayOf(3)

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
        val header = BlockHeader(1, byteArrayOf(), byteArrayOf(), 1, 1, 1, MINED_BLOCK)
        database.block.insert(Block(header, 1))
        resolver = TransactionConflictsResolver(Storage(database).apply { setTransactionSerializer(BaseTransactionSerializer()) })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getTransactionsConflictingWithPendingTransaction_chainOfThree_returnsBothEarlier() {
        insert(a, sequence = 0)
        insert(b, sequence = 1)

        val conflicting = resolver.getTransactionsConflictingWithPendingTransaction(incoming(c, sequence = 2))

        assertEquals(setOf(a, b).hexes(), conflicting.map { it.hash }.hexes())
    }

    @Test
    fun getTransactionsConflictingWithPendingTransaction_twoSharedInputs_returnsOnce() {
        insert(a, sequence = 0)
        database.input.insert(input(a, sequence = 0, index = 1))
        val replacement = FullTransaction(
            Transaction().apply { hash = b },
            listOf(input(b, sequence = 1), input(b, sequence = 1, index = 1)),
            listOf(),
            forceHashUpdate = false
        )

        assertEquals(1, resolver.getTransactionsConflictingWithPendingTransaction(replacement).size)
    }

    @Test
    fun getTransactionsConflictingWithInBlockTransaction_chainOfThree_returnsBothLosers() {
        insert(a, sequence = 0)
        insert(b, sequence = 1)
        insert(c, sequence = 2)

        val conflicting = resolver.getTransactionsConflictingWithInBlockTransaction(incoming(b, sequence = 1))

        assertEquals(setOf(a, c).hexes(), conflicting.map { it.hash }.hexes())
    }

    @Test
    fun isTransactionReplaced_minedWinnerAndPendingLoser_true() {
        insert(a, sequence = 0)
        insert(b, sequence = 1, blockHash = MINED_BLOCK)
        insert(c, sequence = 2)

        assertTrue(resolver.isTransactionReplaced(incoming(a, sequence = 0)))
    }

    @Test
    fun isSpentByMinedTransaction_minedSpender_true() {
        insert(a, sequence = 0, blockHash = MINED_BLOCK)

        assertTrue(resolver.isSpentByMinedTransaction(incoming(b, sequence = 1)))
    }

    @Test
    fun isSpentByMinedTransaction_pendingSpender_false() {
        insert(a, sequence = 0)

        assertFalse(resolver.isSpentByMinedTransaction(incoming(b, sequence = 1)))
    }

    private fun insert(hash: ByteArray, sequence: Long, blockHash: ByteArray? = null) {
        database.transaction.insert(
            Transaction().apply {
                this.hash = hash
                this.blockHash = blockHash
                status = Transaction.Status.RELAYED
                timestamp = 100
                isMine = true
                isOutgoing = true
            }
        )
        database.input.insert(input(hash, sequence))
    }

    private fun incoming(hash: ByteArray, sequence: Long): FullTransaction {
        val header = Transaction().apply { this.hash = hash }
        return FullTransaction(header, listOf(input(hash, sequence)), listOf(), forceHashUpdate = false)
    }

    private fun input(spender: ByteArray, sequence: Long, index: Long = 0) = TransactionInput(
        previousOutputTxHash = SPENT_OUTPUT,
        previousOutputIndex = index,
        sequence = sequence
    ).apply {
        transactionHash = spender
    }

    private fun Iterable<ByteArray>.hexes() = map { it.toHexString() }.toSet()

    private companion object {
        val SPENT_OUTPUT = byteArrayOf(7)
        val MINED_BLOCK = byteArrayOf(9)
    }
}

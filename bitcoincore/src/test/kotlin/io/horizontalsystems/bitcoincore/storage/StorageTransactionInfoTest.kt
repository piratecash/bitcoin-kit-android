package io.horizontalsystems.bitcoincore.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionFilterType
import io.horizontalsystems.bitcoincore.models.TransactionMetadata
import io.horizontalsystems.bitcoincore.models.TransactionType
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.migrations.Migration_28_29
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class StorageTransactionInfoTest {

    private lateinit var database: CoreDatabase
    private lateinit var storage: Storage

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CoreDatabase::class.java
        )
            .addMigrations(Migration_28_29)
            .setTransactionExecutor(Executors.newSingleThreadExecutor())
            .setQueryExecutor(Executors.newSingleThreadExecutor())
            .allowMainThreadQueries()
            .build()

        storage = Storage(database).apply {
            setTransactionSerializer(BaseTransactionSerializer())
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getFullTransactionInfo_missingMetadata_usesDefaultMetadata() {
        val transaction = insertTransaction(hash = byteArrayOf(1, 2, 3))

        val info = storage.getFullTransactionInfo(null, null, 10).single()

        assertArrayEquals(transaction.hash, info.metadata.transactionHash)
        assertEquals(0L, info.metadata.amount)
        assertEquals(TransactionType.Incoming, info.metadata.type)
        assertNull(info.metadata.fee)
    }

    @Test
    fun getFullTransactionInfo_existingMetadata_keepsStoredMetadata() {
        val transaction = insertTransaction(hash = byteArrayOf(4, 5, 6))
        database.transactionMetadata.insert(TransactionMetadata(transaction.hash).apply {
            amount = 123L
            type = TransactionType.Outgoing
            fee = 7L
        })

        val info = storage.getFullTransactionInfo(null, null, 10).single()

        assertArrayEquals(transaction.hash, info.metadata.transactionHash)
        assertEquals(123L, info.metadata.amount)
        assertEquals(TransactionType.Outgoing, info.metadata.type)
        assertEquals(7L, info.metadata.fee)
    }

    @Test
    fun getFullTransactionInfoByHash_missingMetadata_usesDefaultMetadata() {
        val transaction = insertTransaction(hash = byteArrayOf(7, 8, 9))

        val info = requireNotNull(storage.getFullTransactionInfo(transaction.hash))

        assertArrayEquals(transaction.hash, info.metadata.transactionHash)
        assertEquals(0L, info.metadata.amount)
        assertEquals(TransactionType.Incoming, info.metadata.type)
        assertNull(info.metadata.fee)
    }

    @Test
    fun getFullTransaction_preservesStoredHash() {
        storage.setTransactionSerializer(ChangingHashSerializer())
        val transaction = insertTransaction(hash = byteArrayOf(10, 11, 12))

        val fullTransaction = requireNotNull(storage.getFullTransaction(transaction.hash))

        assertArrayEquals(transaction.hash, fullTransaction.header.hash)
    }

    @Test
    fun fullTransactionInfoFullTransaction_preservesStoredHashAndMetadata() {
        storage.setTransactionSerializer(ChangingHashSerializer())
        val transaction = insertTransaction(hash = byteArrayOf(13, 14, 15))
        database.transactionMetadata.insert(TransactionMetadata(transaction.hash).apply {
            amount = 321L
            type = TransactionType.Outgoing
            fee = 9L
        })

        val fullTransaction = requireNotNull(storage.getFullTransactionInfo(transaction.hash)).fullTransaction

        assertArrayEquals(transaction.hash, fullTransaction.header.hash)
        assertEquals(321L, fullTransaction.metadata.amount)
        assertEquals(TransactionType.Outgoing, fullTransaction.metadata.type)
        assertEquals(9L, fullTransaction.metadata.fee)
    }

    @Test
    fun getFullTransactionInfo_sinceTimestamp_includesBoundary() {
        insertTransactions(1_000, 2_000, 3_000)

        assertEquals(listOf(3_000L, 2_000L), timestamps(since = 2_000))
    }

    @Test
    fun getFullTransactionInfo_untilTimestamp_excludesBoundary() {
        insertTransactions(1_000, 2_000, 3_000)

        assertEquals(listOf(2_000L, 1_000L), timestamps(until = 3_000))
    }

    @Test
    fun getFullTransactionInfo_sinceAndUntil_returnsHalfOpenPeriod() {
        insertTransactions(1_000, 2_000, 3_000, 4_000)

        assertEquals(listOf(3_000L, 2_000L), timestamps(since = 2_000, until = 4_000))
    }

    @Test
    fun getFullTransactionInfo_cursorAndPeriod_appliesBoth() {
        val transactions = insertTransactions(1_000, 2_000, 3_000, 4_000)

        assertEquals(listOf(2_000L), timestamps(from = transactions[2], since = 2_000))
    }

    @Test
    fun getFullTransactionInfo_emptyPeriod_returnsEmpty() {
        insertTransactions(1_000, 2_000)

        assertEquals(emptyList<Long>(), timestamps(since = 5_000, until = 6_000))
    }

    @Test
    fun getOldestTransactionTimestamp_noFilter_returnsMinimum() {
        insertTransactions(3_000, 1_000, 2_000)

        assertEquals(1_000L, storage.getOldestTransactionTimestamp(null))
    }

    @Test
    fun getOldestTransactionTimestamp_typeFilter_ignoresOtherTypes() {
        val old = insertTransaction(hash = byteArrayOf(1), timestamp = 1_000)
        val recent = insertTransaction(hash = byteArrayOf(2), timestamp = 2_000)
        database.transactionMetadata.insert(TransactionMetadata(old.hash).apply { type = TransactionType.Outgoing })
        database.transactionMetadata.insert(TransactionMetadata(recent.hash).apply { type = TransactionType.Incoming })

        assertEquals(2_000L, storage.getOldestTransactionTimestamp(TransactionFilterType.Incoming))
    }

    @Test
    fun getOldestTransactionTimestamp_emptyStorage_returnsNull() {
        assertNull(storage.getOldestTransactionTimestamp(null))
    }

    private fun insertTransactions(vararg timestamps: Long): List<Transaction> {
        return timestamps.mapIndexed { index, timestamp ->
            insertTransaction(hash = byteArrayOf(index.toByte()), timestamp = timestamp)
        }
    }

    private fun timestamps(from: Transaction? = null, since: Long? = null, until: Long? = null): List<Long> {
        return storage.getFullTransactionInfo(from, null, null, since, until).map { it.header.timestamp }
    }

    private fun insertTransaction(hash: ByteArray, timestamp: Long = 1_000): Transaction {
        return Transaction().apply {
            uid = hash.joinToString(separator = "")
            this.hash = hash
            this.timestamp = timestamp
            status = Transaction.Status.RELAYED
        }.also(database.transaction::insert)
    }

    private class ChangingHashSerializer : BaseTransactionSerializer() {
        override fun serializeForTransactionHash(transaction: FullTransaction): ByteArray {
            return ByteArray(32) { 7 }
        }
    }
}

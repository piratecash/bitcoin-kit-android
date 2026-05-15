package io.horizontalsystems.bitcoincore.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.models.Transaction
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

    private fun insertTransaction(hash: ByteArray): Transaction {
        return Transaction().apply {
            uid = hash.joinToString(separator = "")
            this.hash = hash
            timestamp = 1_000
            status = Transaction.Status.RELAYED
        }.also(database.transaction::insert)
    }

    private class ChangingHashSerializer : BaseTransactionSerializer() {
        override fun serializeForTransactionHash(transaction: FullTransaction): ByteArray {
            return ByteArray(32) { 7 }
        }
    }
}

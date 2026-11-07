package io.horizontalsystems.bitcoincore.transactions

import io.horizontalsystems.bitcoincore.blocks.IBlockchainDataListener
import io.horizontalsystems.bitcoincore.core.BaseTransactionInfoConverter
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.core.PluginManager
import io.horizontalsystems.bitcoincore.core.TransactionInfoConverter
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.models.InvalidTransaction
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.bitcoincore.models.TransactionMetadata
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransactionInfo
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TransactionInvalidatorTest {
    private val storage = mock<IStorage>()
    private val invalidator = TransactionInvalidator(
        storage,
        TransactionInfoConverter().apply { baseConverter = BaseTransactionInfoConverter(PluginManager()) },
        mock<IBlockchainDataListener>()
    )

    @Test
    fun invalidate_hashOnlyOnPassedTransaction_hashReachesTransactionAndDescendant() {
        val invalidated = invalidate(passedHash = WINNER_HASH, storedHash = null)

        assertConflictingHash(WINNER_HASH, invalidated)
    }

    @Test
    fun invalidate_passedHashNull_keepsStoredHash() {
        val invalidated = invalidate(passedHash = null, storedHash = STORED_HASH)

        assertConflictingHash(STORED_HASH, invalidated)
    }

    @Test
    fun invalidate_passedAndStoredHashesDiffer_passedHashWins() {
        val invalidated = invalidate(passedHash = WINNER_HASH, storedHash = STORED_HASH)

        assertConflictingHash(WINNER_HASH, invalidated)
    }

    private fun invalidate(passedHash: ByteArray?, storedHash: ByteArray?): List<InvalidTransaction> {
        whenever(storage.getTransaction(TRANSACTION_HASH)).thenReturn(transaction(TRANSACTION_HASH, storedHash))
        whenever(storage.getDescendantTransactionsFullInfo(TRANSACTION_HASH)).thenReturn(
            listOf(fullInfo(TRANSACTION_HASH, storedHash), fullInfo(DESCENDANT_HASH, storedHash))
        )

        invalidator.invalidate(transaction(TRANSACTION_HASH, passedHash))

        val captor = argumentCaptor<List<InvalidTransaction>>()
        verify(storage).moveTransactionToInvalidTransactions(captor.capture())
        return captor.firstValue
    }

    private fun assertConflictingHash(expected: ByteArray, invalidated: List<InvalidTransaction>) {
        assertEquals(
            listOf(TRANSACTION_HASH.toReversedHex(), DESCENDANT_HASH.toReversedHex()),
            invalidated.map { it.hash.toReversedHex() }
        )
        invalidated.forEach { record ->
            assertContentEquals(expected, record.conflictingTxHash)
            assertEquals(expected.toReversedHex(), TransactionInfo(record.serializedTxInfo).conflictingTxHash)
        }
    }

    private fun transaction(transactionHash: ByteArray, conflictingHash: ByteArray?) = Transaction().apply {
        hash = transactionHash
        conflictingTxHash = conflictingHash
    }

    private fun fullInfo(transactionHash: ByteArray, conflictingHash: ByteArray?) = FullTransactionInfo(
        block = null,
        header = transaction(transactionHash, conflictingHash),
        inputs = emptyList(),
        outputs = emptyList(),
        metadata = TransactionMetadata(transactionHash),
        transactionSerializer = BaseTransactionSerializer()
    )

    private companion object {
        val TRANSACTION_HASH = byteArrayOf(1, 10)
        val DESCENDANT_HASH = byteArrayOf(2, 20)
        val WINNER_HASH = byteArrayOf(3, 30)
        val STORED_HASH = byteArrayOf(4, 40)
    }
}

package io.horizontalsystems.bitcoincore.transactions

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import io.horizontalsystems.bitcoincore.Fixtures
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.transactions.builder.TransactionBuilder
import io.horizontalsystems.bitcoincore.transactions.builder.TransactionSigner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class TransactionCreatorBroadcastTest {

    private val builder = mock<TransactionBuilder>()
    private val processor = mock<PendingTransactionProcessor>()
    private val transactionSender = mock<TransactionSender>()
    private val transactionSigner = mock<TransactionSigner>()
    private val bloomFilterManager = mock<BloomFilterManager>()

    private val foreignTransaction = FullTransaction(
        header = Transaction(),
        inputs = emptyList<TransactionInput>(),
        outputs = emptyList<TransactionOutput>(),
        transactionSerializer = BaseTransactionSerializer(),
    )

    @Test
    fun broadcastRawTransaction_validHex_delegatesToSenderWithDeserializedTransaction() = runBlocking {
        val serializer = RecordingTransactionSerializer().apply { deserializedTransaction = foreignTransaction }
        val creator = creatorWith(serializer)

        val result = creator.broadcastRawTransaction(RAW_HEX)

        assertSame(foreignTransaction, result)
        verify(transactionSender).broadcastRawTransaction(foreignTransaction, RAW_HEX)
    }

    @Test
    fun broadcastRawTransaction_validHex_doesNotStoreTransaction() = runBlocking {
        val serializer = RecordingTransactionSerializer().apply { deserializedTransaction = foreignTransaction }
        val creator = creatorWith(serializer)

        creator.broadcastRawTransaction(RAW_HEX)

        verify(processor, never()).processCreated(any())
        verify(transactionSender, never()).sendPendingTransactions()
    }

    @Test
    fun broadcastRawTransaction_realHex_deserializesAndDelegatesOriginalHex() = runBlocking {
        val serializer = BaseTransactionSerializer()
        val rawHex = serializer.serialize(Fixtures.transactionP2WPKH).toHexString()
        val creator = creatorWith(serializer)

        val result = creator.broadcastRawTransaction(rawHex)

        // Proves the real hex -> bytes -> deserialize path runs: the parsed transaction has the
        // same txid as the source, and the sender receives the untouched original hex.
        assertArrayEquals(Fixtures.transactionP2WPKH.header.hash, result.header.hash)
        verify(transactionSender).broadcastRawTransaction(any(), eq(rawHex))
    }

    @Test
    fun broadcastRawTransaction_invalidHex_throwsAndDoesNotDelegate() {
        val creator = creatorWith(BaseTransactionSerializer())

        assertThrows(TransactionCreator.TransactionCreationException::class.java) {
            runBlocking { creator.broadcastRawTransaction("nothex!!") }
        }

        runBlocking { verify(transactionSender, never()).broadcastRawTransaction(any(), any()) }
    }

    @Test
    fun broadcastRawTransaction_oddLengthHex_throwsAndDoesNotDelegate() {
        val serializer = BaseTransactionSerializer()
        // A valid transaction hex with one extra nibble would otherwise be silently truncated.
        val oddHex = serializer.serialize(Fixtures.transactionP2WPKH).toHexString() + "0"
        val creator = creatorWith(serializer)

        assertThrows(TransactionCreator.TransactionCreationException::class.java) {
            runBlocking { creator.broadcastRawTransaction(oddHex) }
        }

        runBlocking { verify(transactionSender, never()).broadcastRawTransaction(any(), any()) }
    }

    private fun creatorWith(serializer: BaseTransactionSerializer) = TransactionCreator(
        builder = builder,
        processor = processor,
        transactionSender = transactionSender,
        transactionSigner = transactionSigner,
        bloomFilterManager = bloomFilterManager,
        transactionSerializer = serializer,
    )

    private class RecordingTransactionSerializer : BaseTransactionSerializer() {
        var deserializedTransaction: FullTransaction? = null

        override fun deserialize(input: BitcoinInputMarkable): FullTransaction {
            return deserializedTransaction ?: error("No deserialized transaction configured")
        }

        override fun serializeForTransactionHash(transaction: FullTransaction): ByteArray {
            return byteArrayOf()
        }
    }

    private companion object {
        const val RAW_HEX = "01020304"
    }
}

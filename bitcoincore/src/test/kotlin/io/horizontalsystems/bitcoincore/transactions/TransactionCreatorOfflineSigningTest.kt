package io.horizontalsystems.bitcoincore.transactions

import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import io.horizontalsystems.bitcoincore.core.IPluginData
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionDataSortType
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoincore.transactions.builder.MutableTransaction
import io.horizontalsystems.bitcoincore.transactions.builder.SignedTransactionData
import io.horizontalsystems.bitcoincore.transactions.builder.TransactionBuilder
import io.horizontalsystems.bitcoincore.transactions.builder.TransactionSigner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransactionCreatorOfflineSigningTest {

    private val builder = mock<TransactionBuilder>()
    private val processor = mock<PendingTransactionProcessor>()
    private val transactionSender = mock<TransactionSender>()
    private val transactionSigner = mock<TransactionSigner>()
    private val bloomFilterManager = mock<BloomFilterManager>()
    private val transactionSerializer = RecordingTransactionSerializer()
    private val mutableTransaction = MutableTransaction()
    private val pluginData = emptyMap<Byte, IPluginData>()
    private val filters = UtxoFilters()

    private val transactionCreator = TransactionCreator(
        builder = builder,
        processor = processor,
        transactionSender = transactionSender,
        transactionSigner = transactionSigner,
        bloomFilterManager = bloomFilterManager,
        transactionSerializer = transactionSerializer,
    )

    @Before
    fun setup() {
        whenever(
            builder.buildTransaction(
                toAddress = TO_ADDRESS,
                memo = MEMO,
                value = VALUE,
                feeRate = FEE_RATE,
                senderPay = SENDER_PAY,
                sortType = SORT_TYPE,
                unspentOutputs = null,
                pluginData = pluginData,
                rbfEnabled = RBF_ENABLED,
                changeToFirstInput = CHANGE_TO_FIRST_INPUT,
                filters = filters,
            )
        ).thenReturn(mutableTransaction)
    }

    @Test
    fun createSigned_validRequest_delegatesSigningAndBuildsTransaction() {
        runBlocking {
            val result = createSignedTransaction()

            assertSame(mutableTransaction.transaction, result.header)
            verify(transactionSigner).sign(mutableTransaction)
        }
    }

    @Test
    fun createSigned_validRequest_doesNotStoreOrBroadcastTransaction() {
        runBlocking {
            createSignedTransaction()
        }

        verify(processor, never()).processCreated(any())
        verify(transactionSender, never()).sendPendingTransactions()
    }

    @Test
    fun createSigned_fullTransactionSignerResult_usesSerializedTransaction() {
        val signedTransaction = FullTransaction(
            header = Transaction(),
            inputs = emptyList<TransactionInput>(),
            outputs = emptyList<TransactionOutput>(),
            transactionSerializer = transactionSerializer,
        )
        transactionSerializer.deserializedTransaction = signedTransaction

        runBlocking {
            whenever(transactionSigner.sign(mutableTransaction)).thenReturn(
                SignedTransactionData(
                    serializedTx = SIGNED_TRANSACTION_HEX,
                    signatures = emptyList(),
                )
            )

            val result = createSignedTransaction()

            assertSame(signedTransaction, result)
            assertEquals(Transaction.Status.NEW, result.header.status)
            assertTrue(result.header.isMine)
            assertTrue(result.header.isOutgoing)
        }
    }

    @Test
    fun serialize_fullTransaction_delegatesToSerializer() {
        val transaction = FullTransaction(
            header = Transaction(),
            inputs = emptyList<TransactionInput>(),
            outputs = emptyList<TransactionOutput>(),
            transactionSerializer = transactionSerializer,
        )

        val serialized = transactionCreator.serialize(transaction, withWitness = false)

        assertArrayEquals(SERIALIZED_TRANSACTION, serialized)
        assertSame(transaction, transactionSerializer.lastSerializedTransaction)
        assertFalse(transactionSerializer.lastWithWitness)
    }

    private suspend fun createSignedTransaction(): FullTransaction {
        return transactionCreator.createSigned(
            toAddress = TO_ADDRESS,
            memo = MEMO,
            value = VALUE,
            feeRate = FEE_RATE,
            senderPay = SENDER_PAY,
            sortType = SORT_TYPE,
            unspentOutputs = null,
            pluginData = pluginData,
            rbfEnabled = RBF_ENABLED,
            changeToFirstInput = CHANGE_TO_FIRST_INPUT,
            filters = filters,
        )
    }

    private class RecordingTransactionSerializer : BaseTransactionSerializer() {
        var deserializedTransaction: FullTransaction? = null

        var lastSerializedTransaction: FullTransaction? = null
            private set
        var lastWithWitness = true
            private set

        override fun serialize(transaction: FullTransaction, withWitness: Boolean): ByteArray {
            lastSerializedTransaction = transaction
            lastWithWitness = withWitness
            return SERIALIZED_TRANSACTION
        }

        override fun deserialize(input: BitcoinInputMarkable): FullTransaction {
            return deserializedTransaction ?: error("No deserialized transaction configured")
        }

        override fun serializeForTransactionHash(transaction: FullTransaction): ByteArray {
            return byteArrayOf()
        }
    }

    private companion object {
        const val TO_ADDRESS = "bc1qofflineaddress"
        const val MEMO = "offline memo"
        const val VALUE = 100_000L
        const val FEE_RATE = 5
        const val SENDER_PAY = true
        const val RBF_ENABLED = true
        const val CHANGE_TO_FIRST_INPUT = false
        val SORT_TYPE = TransactionDataSortType.Bip69
        val SERIALIZED_TRANSACTION = byteArrayOf(1, 2, 3, 4)
        const val SIGNED_TRANSACTION_HEX = "01020304"
    }
}

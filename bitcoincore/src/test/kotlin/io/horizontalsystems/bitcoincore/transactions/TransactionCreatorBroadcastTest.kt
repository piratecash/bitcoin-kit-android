package io.horizontalsystems.bitcoincore.transactions

import com.eclipsesource.json.JsonValue
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import io.horizontalsystems.bitcoincore.Fixtures
import io.horizontalsystems.bitcoincore.apisync.blockchair.Api
import io.horizontalsystems.bitcoincore.apisync.blockchair.ApiTransaction
import io.horizontalsystems.bitcoincore.apisync.blockchair.FullApiTransaction
import io.horizontalsystems.bitcoincore.apisync.model.BlockHeaderItem
import io.horizontalsystems.bitcoincore.apisync.model.TransactionItem
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.models.RawTransactionBroadcastStatus
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
import org.junit.Assert.assertTrue
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
    fun broadcastRawTransaction_validHex_delegatesToSenderWithDeserializedTransaction() {
        runBlocking {
        val serializer = RecordingTransactionSerializer().apply { deserializedTransaction = foreignTransaction }
        val creator = creatorWith(serializer)
        givenBroadcastStatus(RawTransactionBroadcastStatus.Queued)

        val result = creator.broadcastRawTransaction(RAW_HEX)

        assertSame(foreignTransaction, result.transaction)
        assertSame(RawTransactionBroadcastStatus.Queued, result.status)
        verify(transactionSender).broadcastRawTransaction(foreignTransaction, RAW_HEX)
    }
    }

    @Test
    fun broadcastRawTransaction_validHex_doesNotStoreTransaction() = runBlocking {
        val serializer = RecordingTransactionSerializer().apply { deserializedTransaction = foreignTransaction }
        val creator = creatorWith(serializer)
        givenBroadcastStatus()

        creator.broadcastRawTransaction(RAW_HEX)

        verify(processor, never()).processCreated(any())
        verify(transactionSender, never()).sendPendingTransactions()
    }

    @Test
    fun broadcastRawTransaction_realHex_deserializesAndDelegatesOriginalHex() {
        runBlocking {
        val serializer = BaseTransactionSerializer()
        val rawHex = serializer.serialize(Fixtures.transactionP2WPKH).toHexString()
        val creator = creatorWith(serializer)
        givenBroadcastStatus()

        val result = creator.broadcastRawTransaction(rawHex)

        // Proves the real hex -> bytes -> deserialize path runs: the parsed transaction has the
        // same txid as the source, and the sender receives the untouched original hex.
        assertArrayEquals(Fixtures.transactionP2WPKH.header.hash, result.transaction.header.hash)
        verify(transactionSender).broadcastRawTransaction(any(), eq(rawHex))
    }
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

    @Test
    fun broadcastRawTransaction_existenceCheckFindsTxid_returnsAlreadyKnownWithoutSending() {
        runBlocking {
        val serializer = RecordingTransactionSerializer().apply { deserializedTransaction = foreignTransaction }
        val txid = foreignTransaction.header.hash.toReversedHex()
        val api = FakeExistenceCheckApi(knownTxids = setOf(txid))
        val creator = creatorWith(serializer, existenceCheckApi = api)

        val result = creator.broadcastRawTransaction(RAW_HEX)

        assertSame(RawTransactionBroadcastStatus.AlreadyKnown, result.status)
        verify(transactionSender, never()).broadcastRawTransaction(any(), any())
    }
    }

    @Test
    fun broadcastRawTransaction_existenceCheckHangs_failsOpenWithinTimeout() {
        runBlocking {
        val serializer = RecordingTransactionSerializer().apply { deserializedTransaction = foreignTransaction }
        // The fake blocks far longer than the existence-check timeout, simulating an
        // unresponsive API with no coroutine suspension point (like the real OkHttp call).
        val api = FakeExistenceCheckApi(delayMillis = TEST_TIMEOUT_MILLIS * 10)
        val creator = creatorWith(serializer, existenceCheckApi = api, existenceCheckTimeoutMillis = TEST_TIMEOUT_MILLIS)
        givenBroadcastStatus(RawTransactionBroadcastStatus.Submitted)

        val startTime = System.currentTimeMillis()
        val result = creator.broadcastRawTransaction(RAW_HEX)
        val elapsedMillis = System.currentTimeMillis() - startTime

        // Fails open: the normal broadcast proceeds well before the fake API call would return.
        assertTrue("Expected to return in well under ${api.delayMillis}ms, took ${elapsedMillis}ms", elapsedMillis < api.delayMillis / 2)
        assertSame(RawTransactionBroadcastStatus.Submitted, result.status)
        verify(transactionSender).broadcastRawTransaction(foreignTransaction, RAW_HEX)
    }
    }

    private fun creatorWith(
        serializer: BaseTransactionSerializer,
        existenceCheckApi: Api? = null,
        existenceCheckTimeoutMillis: Long = EXISTENCE_CHECK_TIMEOUT_MS,
    ) = TransactionCreator(
        builder = builder,
        processor = processor,
        transactionSender = transactionSender,
        transactionSigner = transactionSigner,
        bloomFilterManager = bloomFilterManager,
        transactionSerializer = serializer,
        existenceCheckApi = existenceCheckApi,
        existenceCheckTimeoutMillis = existenceCheckTimeoutMillis,
    )

    private fun givenBroadcastStatus(
        status: RawTransactionBroadcastStatus = RawTransactionBroadcastStatus.Submitted,
    ) {
        runBlocking {
            whenever(transactionSender.broadcastRawTransaction(any(), any())).thenReturn(status)
        }
    }

    private class RecordingTransactionSerializer : BaseTransactionSerializer() {
        var deserializedTransaction: FullTransaction? = null

        override fun deserialize(input: BitcoinInputMarkable): FullTransaction {
            return deserializedTransaction ?: error("No deserialized transaction configured")
        }

        override fun serializeForTransactionHash(transaction: FullTransaction): ByteArray {
            return byteArrayOf()
        }
    }

    // Fake existence-check provider. `getTransactions` optionally blocks via `Thread.sleep`
    // rather than `delay()`, matching the real ApiManager/OkHttp call it stands in for: a
    // blocking call with no coroutine suspension point of its own.
    private class FakeExistenceCheckApi(
        private val knownTxids: Set<String> = emptySet(),
        val delayMillis: Long = 0L,
    ) : Api {
        override fun transactions(addresses: List<String>, stopHeight: Int?): List<TransactionItem> =
            throw UnsupportedOperationException("Not used by existence-check tests")

        override suspend fun getTransactions(hashes: List<String>): List<FullApiTransaction> {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis)
            }
            return hashes.filter { it in knownTxids }.map { hash ->
                FullApiTransaction(
                    transaction = ApiTransaction(hash = hash, date = "", time = "", fee = 0L),
                    inputs = emptyList(),
                    outputs = emptyList(),
                )
            }
        }

        override fun blockHashes(heights: List<Int>): Map<Int, String> =
            throw UnsupportedOperationException("Not used by existence-check tests")

        override fun lastBlockHeader(): BlockHeaderItem =
            throw UnsupportedOperationException("Not used by existence-check tests")

        override fun broadcastTransaction(rawTransactionHex: String): JsonValue =
            throw UnsupportedOperationException("Not used by existence-check tests")
    }

    private companion object {
        const val RAW_HEX = "01020304"
        const val EXISTENCE_CHECK_TIMEOUT_MS = 2500L
        const val TEST_TIMEOUT_MILLIS = 200L
    }
}

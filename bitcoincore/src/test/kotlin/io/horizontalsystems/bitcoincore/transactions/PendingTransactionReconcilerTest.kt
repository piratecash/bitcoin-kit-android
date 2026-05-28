package io.horizontalsystems.bitcoincore.transactions

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonValue
import io.horizontalsystems.bitcoincore.apisync.blockchair.Api
import io.horizontalsystems.bitcoincore.apisync.blockchair.ApiInput
import io.horizontalsystems.bitcoincore.apisync.blockchair.ApiOutput
import io.horizontalsystems.bitcoincore.apisync.blockchair.ApiTransaction
import io.horizontalsystems.bitcoincore.apisync.blockchair.FullApiTransaction
import io.horizontalsystems.bitcoincore.apisync.model.BlockHeaderItem
import io.horizontalsystems.bitcoincore.apisync.model.TransactionItem
import io.horizontalsystems.bitcoincore.blocks.IBlockchainDataListener
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toReversedByteArray
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.models.BlockHash
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingTransactionReconcilerTest {
    private val storage = mock<IStorage>()
    private val dataListener = mock<IBlockchainDataListener>()
    private val invalidatedTransactions = mutableListOf<Transaction>()
    private val statusProvider = FakeStatusProvider()

    private val reconciler = PendingTransactionReconciler(
        storage = storage,
        statusProvider = statusProvider,
        dataListener = dataListener,
        invalidateOutgoing = invalidatedTransactions::add,
        logTag = "test",
        coroutineDispatcher = Dispatchers.Unconfined,
        minimumPendingAgeSeconds = MINIMUM_PENDING_AGE_SECONDS
    )

    @Test
    fun reconcile_staleMissingIncomingTransaction_deletesAndNotifies() {
        val missingTransaction = transaction(hashByte = 1, timestamp = 100)
        val existingTransaction = transaction(hashByte = 2, timestamp = 100)
        whenever(storage.getRelayedPendingTransactions(Transaction.Status.RELAYED))
            .thenReturn(listOf(missingTransaction, existingTransaction))
        whenever(storage.deleteRelayedPendingTransactions(listOf(missingTransaction))).thenReturn(listOf(missingTransaction))
        statusProvider.statuses = listOf(PendingTransactionStatus(existingTransaction.hash.toReversedHex(), null))

        reconcile(nowSeconds = 1_000)

        verify(storage).deleteRelayedPendingTransactions(listOf(missingTransaction))
        verify(dataListener).onTransactionsDelete(listOf(missingTransaction.hash.toReversedHex()))
    }

    @Test
    fun reconcile_staleMissingOutgoingTransaction_invalidatesTransaction() {
        val transaction = transaction(hashByte = 1, timestamp = 100, isOutgoing = true)
        whenever(storage.getRelayedPendingTransactions(Transaction.Status.RELAYED)).thenReturn(listOf(transaction))
        whenever(storage.getTransactionInputs(transaction)).thenReturn(listOf(input()))

        reconcile(nowSeconds = 1_000)

        assertEquals(listOf(transaction), invalidatedTransactions)
        verify(storage, never()).deleteRelayedPendingTransactions(any())
        verify(dataListener, never()).onTransactionsDelete(any())
    }

    @Test
    fun reconcile_malformedOutgoingTransactionWithoutInputs_deletesAndNotifies() {
        val transaction = transaction(hashByte = 1, timestamp = 900, isOutgoing = true)
        whenever(storage.getRelayedPendingTransactions(Transaction.Status.RELAYED)).thenReturn(listOf(transaction))
        whenever(storage.getTransactionInputs(transaction)).thenReturn(emptyList())
        whenever(storage.deleteRelayedPendingTransactions(listOf(transaction))).thenReturn(listOf(transaction))

        reconcile(nowSeconds = 1_000)

        verify(storage).deleteRelayedPendingTransactions(listOf(transaction))
        verify(dataListener).onTransactionsDelete(listOf(transaction.hash.toReversedHex()))
        assertEquals(emptyList(), invalidatedTransactions)
        assertEquals(emptyList(), statusProvider.requests)
    }

    @Test
    fun reconcile_malformedAndWellFormedOutgoingTransactions_deletesMalformedAndLooksUpWellFormed() {
        val malformedTransaction = transaction(hashByte = 1, timestamp = 900, isOutgoing = true)
        val wellFormedTransaction = transaction(hashByte = 2, timestamp = 900, isOutgoing = true)
        whenever(storage.getRelayedPendingTransactions(Transaction.Status.RELAYED))
            .thenReturn(listOf(malformedTransaction, wellFormedTransaction))
        whenever(storage.getTransactionInputs(malformedTransaction)).thenReturn(emptyList())
        whenever(storage.getTransactionInputs(wellFormedTransaction)).thenReturn(listOf(input()))
        whenever(storage.deleteRelayedPendingTransactions(listOf(malformedTransaction))).thenReturn(listOf(malformedTransaction))
        statusProvider.statuses = listOf(PendingTransactionStatus(wellFormedTransaction.hash.toReversedHex(), null))

        reconcile(nowSeconds = 1_000)

        verify(storage).deleteRelayedPendingTransactions(listOf(malformedTransaction))
        verify(dataListener).onTransactionsDelete(listOf(malformedTransaction.hash.toReversedHex()))
        assertEquals(listOf(listOf(wellFormedTransaction.hash.toReversedHex())), statusProvider.requests)
        assertEquals(emptyList(), invalidatedTransactions)
    }

    @Test
    fun reconcile_freshMissingTransaction_keepsTransaction() {
        val transaction = transaction(hashByte = 1, timestamp = 500)
        whenever(storage.getRelayedPendingTransactions(Transaction.Status.RELAYED)).thenReturn(listOf(transaction))

        reconcile(nowSeconds = 1_000)

        verify(storage, never()).deleteRelayedPendingTransactions(any())
        verify(dataListener, never()).onTransactionsDelete(any())
    }

    @Test
    fun reconcile_exactMinimumAgeMissingTransaction_deletesAndNotifies() {
        val transaction = transaction(hashByte = 1, timestamp = 400)
        whenever(storage.getRelayedPendingTransactions(Transaction.Status.RELAYED)).thenReturn(listOf(transaction))
        whenever(storage.deleteRelayedPendingTransactions(listOf(transaction))).thenReturn(listOf(transaction))

        reconcile(nowSeconds = 1_000)

        verify(storage).deleteRelayedPendingTransactions(listOf(transaction))
        verify(dataListener).onTransactionsDelete(listOf(transaction.hash.toReversedHex()))
    }

    @Test
    fun reconcile_existingTransaction_keepsTransaction() {
        val transaction = transaction(hashByte = 1, timestamp = 100)
        whenever(storage.getRelayedPendingTransactions(Transaction.Status.RELAYED)).thenReturn(listOf(transaction))
        statusProvider.statuses = listOf(PendingTransactionStatus(transaction.hash.toReversedHex(), null))

        reconcile(nowSeconds = 1_000)

        verify(storage, never()).deleteRelayedPendingTransactions(any())
        verify(dataListener, never()).onTransactionsDelete(any())
    }

    @Test
    fun reconcile_statusProviderFails_keepsTransaction() {
        val transaction = transaction(hashByte = 1, timestamp = 100)
        whenever(storage.getRelayedPendingTransactions(Transaction.Status.RELAYED)).thenReturn(listOf(transaction))
        statusProvider.error = IllegalStateException("api failed")

        reconcile(nowSeconds = 1_000)

        verify(storage, never()).deleteRelayedPendingTransactions(any())
        verify(dataListener, never()).onTransactionsDelete(any())
    }

    @Test
    fun reconcile_statusProviderCancelled_propagatesCancellation() {
        val transaction = transaction(hashByte = 1, timestamp = 100)
        whenever(storage.getRelayedPendingTransactions(Transaction.Status.RELAYED)).thenReturn(listOf(transaction))
        statusProvider.error = CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            reconcile(nowSeconds = 1_000)
        }
        verify(storage, never()).deleteRelayedPendingTransactions(any())
        verify(dataListener, never()).onTransactionsDelete(any())
    }

    @Test
    fun reconcile_confirmedPendingTransaction_addsBlockHashAndKeepsTransaction() {
        val transaction = transaction(hashByte = 1, timestamp = 100)
        val blockHash = BlockHash(BLOCK_HASH.toReversedByteArray(), 123)
        val confirmedBlocksFound = mutableListOf<Unit>()
        reconciler.onConfirmedBlocksFound = { confirmedBlocksFound += Unit }
        whenever(storage.getRelayedPendingTransactions(Transaction.Status.RELAYED)).thenReturn(listOf(transaction))
        statusProvider.statuses = listOf(PendingTransactionStatus(transaction.hash.toReversedHex(), blockHash))

        reconcile(nowSeconds = 1_000)

        verify(storage).addBlockHashes(listOf(blockHash))
        verify(storage, never()).deleteRelayedPendingTransactions(any())
        verify(dataListener, never()).onTransactionsDelete(any())
        assertEquals(listOf(Unit), confirmedBlocksFound)
    }

    @Test
    fun reconcile_freshConfirmedPendingTransaction_addsBlockHashAndKeepsTransaction() {
        val transaction = transaction(hashByte = 1, timestamp = 500)
        val blockHash = BlockHash(BLOCK_HASH.toReversedByteArray(), 123)
        whenever(storage.getRelayedPendingTransactions(Transaction.Status.RELAYED)).thenReturn(listOf(transaction))
        statusProvider.statuses = listOf(PendingTransactionStatus(transaction.hash.toReversedHex(), blockHash))

        reconcile(nowSeconds = 1_000)

        verify(storage).addBlockHashes(listOf(blockHash))
        verify(storage, never()).deleteRelayedPendingTransactions(any())
        verify(dataListener, never()).onTransactionsDelete(any())
    }

    @Test
    fun reconcile_confirmedPendingTransactionBlockAlreadyQueued_doesNotNotifyRestart() {
        val transaction = transaction(hashByte = 1, timestamp = 100)
        val blockHash = BlockHash(BLOCK_HASH.toReversedByteArray(), 123)
        val confirmedBlocksFound = mutableListOf<Unit>()
        reconciler.onConfirmedBlocksFound = { confirmedBlocksFound += Unit }
        whenever(storage.getRelayedPendingTransactions(Transaction.Status.RELAYED)).thenReturn(listOf(transaction))
        whenever(storage.hasBlockHash(blockHash.headerHash)).thenReturn(true)
        statusProvider.statuses = listOf(PendingTransactionStatus(transaction.hash.toReversedHex(), blockHash))

        reconcile(nowSeconds = 1_000)

        verify(storage, never()).addBlockHashes(any())
        assertEquals(emptyList(), confirmedBlocksFound)
    }

    @Test
    fun reconcile_pendingTransactionConfirmedBeforeDelete_doesNotNotifyDelete() {
        val transaction = transaction(hashByte = 1, timestamp = 100)
        whenever(storage.getRelayedPendingTransactions(Transaction.Status.RELAYED)).thenReturn(listOf(transaction))
        whenever(storage.deleteRelayedPendingTransactions(listOf(transaction))).thenReturn(emptyList())

        reconcile(nowSeconds = 1_000)

        verify(storage).deleteRelayedPendingTransactions(listOf(transaction))
        verify(dataListener, never()).onTransactionsDelete(any())
    }

    @Test
    fun reconcile_noStatusProvider_keepsTransactionsUntouched() {
        val reconciler = PendingTransactionReconciler(
            storage = storage,
            statusProvider = null,
            dataListener = dataListener,
            invalidateOutgoing = invalidatedTransactions::add,
            logTag = "test",
            coroutineDispatcher = Dispatchers.Unconfined,
            minimumPendingAgeSeconds = MINIMUM_PENDING_AGE_SECONDS
        )

        runBlocking { reconciler.reconcile(nowSeconds = 1_000) }

        verify(storage, never()).getRelayedPendingTransactions(any())
        verify(storage, never()).deleteRelayedPendingTransactions(any())
        verify(dataListener, never()).onTransactionsDelete(any())
    }

    @Test
    fun transactionStatuses_partialMissingHashes_queriesOneByOne() = runBlocking {
        val api = FakeApi(
            mapOf(
                "known-1" to fullApiTransaction("known-1"),
                "known-2" to fullApiTransaction("known-2")
            )
        )
        val provider = BlockchairPendingTransactionStatusProvider(api, requestDelayMillis = 0)

        val result = provider.transactionStatuses(listOf("known-1", "missing", "known-2"))

        assertEquals(listOf(PendingTransactionStatus("known-1", null), PendingTransactionStatus("known-2", null)), result.statuses)
        assertEquals(setOf("known-1", "missing", "known-2"), result.checkedHashes)
        assertEquals(listOf(listOf("known-1"), listOf("missing"), listOf("known-2")), api.requests)
    }

    @Test
    fun transactionStatuses_confirmedTransaction_addsBlockHash() = runBlocking {
        val api = FakeApi(
            transactionsByHash = mapOf("known" to fullApiTransaction("known", blockId = 123)),
            blockHashesByHeight = mapOf(123 to BLOCK_HASH)
        )
        val provider = BlockchairPendingTransactionStatusProvider(api, requestDelayMillis = 0)

        val result = provider.transactionStatuses(listOf("known"))

        assertEquals("known", result.statuses.single().hash)
        assertEquals(123, result.statuses.single().blockHash?.height)
        assertContentEquals(BLOCK_HASH.toReversedByteArray(), result.statuses.single().blockHash?.headerHash)
    }

    @Test
    fun transactionStatuses_confirmedTransaction_fetchesBlockHashOnBlockingDispatcher() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, BLOCK_HASH_DISPATCHER_THREAD)
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val api = FakeApi(
                transactionsByHash = mapOf("known" to fullApiTransaction("known", blockId = 123)),
                blockHashesByHeight = mapOf(123 to BLOCK_HASH)
            )
            val provider = BlockchairPendingTransactionStatusProvider(
                api = api,
                requestDelayMillis = 0,
                blockingDispatcher = dispatcher
            )

            runBlocking {
                provider.transactionStatuses(listOf("known"))
            }

            assertTrue(api.blockHashThreadNames.single().startsWith(BLOCK_HASH_DISPATCHER_THREAD))
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun transactionStatuses_unconfirmedTransaction_doesNotRequestBlockHash() = runBlocking {
        val api = FakeApi(
            transactionsByHash = mapOf("known" to fullApiTransaction("known", blockId = -1)),
            blockHashesByHeight = mapOf(-1 to BLOCK_HASH)
        )
        val provider = BlockchairPendingTransactionStatusProvider(api, requestDelayMillis = 0)

        val result = provider.transactionStatuses(listOf("known"))

        assertEquals(listOf(PendingTransactionStatus("known", null)), result.statuses)
        assertEquals(emptyList(), api.blockHashRequests)
    }

    @Test
    fun transactionStatuses_blockHashRequestFails_returnsExistingTransactionWithoutBlockHash() = runBlocking {
        val api = FakeApi(
            transactionsByHash = mapOf("known" to fullApiTransaction("known", blockId = 123)),
            blockHashError = IllegalStateException("api failed")
        )
        val provider = BlockchairPendingTransactionStatusProvider(api, requestDelayMillis = 0)

        val result = provider.transactionStatuses(listOf("known"))

        assertEquals(listOf(PendingTransactionStatus("known", null)), result.statuses)
    }

    @Test
    fun transactionStatuses_transactionRequestFails_returnsOnlyCheckedHashes() = runBlocking {
        val api = FakeApi(
            transactionsByHash = mapOf("known" to fullApiTransaction("known")),
            transactionErrorHashes = setOf("failed")
        )
        val provider = BlockchairPendingTransactionStatusProvider(api, requestDelayMillis = 0)

        val result = provider.transactionStatuses(listOf("known", "failed", "unchecked"))

        assertEquals(listOf(PendingTransactionStatus("known", null)), result.statuses)
        assertEquals(setOf("known"), result.checkedHashes)
        assertEquals(listOf(listOf("known"), listOf("failed")), api.requests)
    }

    @Test
    fun create_unsupportedChain_returnsNull() {
        assertNull(BlockchairPendingTransactionStatusProvider.create(FakeApi(emptyMap()), ""))
        assertNull(BlockchairPendingTransactionStatusProvider.create(FakeApi(emptyMap()), "cosanta"))
        assertNull(BlockchairPendingTransactionStatusProvider.create(FakeApi(emptyMap()), "bitcoin/testnet"))
    }

    private fun reconcile(nowSeconds: Long) = runBlocking {
        reconciler.reconcile(nowSeconds)
    }

    private fun transaction(hashByte: Byte, timestamp: Long, isOutgoing: Boolean = false): Transaction {
        return Transaction().apply {
            hash = byteArrayOf(hashByte)
            this.timestamp = timestamp
            isMine = true
            this.isOutgoing = isOutgoing
            status = Transaction.Status.RELAYED
        }
    }

    private fun transaction(
        hashByte: Int,
        timestamp: Long,
        isOutgoing: Boolean = false
    ): Transaction {
        return transaction(hashByte.toByte(), timestamp, isOutgoing)
    }

    private fun input(): TransactionInput {
        return TransactionInput(
            previousOutputTxHash = byteArrayOf(9),
            previousOutputIndex = 0,
            sequence = 0
        )
    }

    private class FakeStatusProvider : PendingTransactionStatusProvider {
        var statuses = emptyList<PendingTransactionStatus>()
        var error: Throwable? = null
        val requests = mutableListOf<List<String>>()

        override suspend fun transactionStatuses(hashes: List<String>): PendingTransactionLookupResult {
            requests += hashes
            error?.let { throw it }
            return PendingTransactionLookupResult(
                checkedHashes = hashes.toSet(),
                statuses = statuses
            )
        }
    }

    private class FakeApi(
        private val transactionsByHash: Map<String, FullApiTransaction>,
        private val blockHashesByHeight: Map<Int, String> = emptyMap(),
        private val blockHashError: Throwable? = null,
        private val transactionErrorHashes: Set<String> = emptySet()
    ) : Api {
        val requests = mutableListOf<List<String>>()
        val blockHashRequests = mutableListOf<List<Int>>()
        val blockHashThreadNames = mutableListOf<String>()

        override fun transactions(addresses: List<String>, stopHeight: Int?): List<TransactionItem> {
            error("Not implemented")
        }

        override suspend fun getTransactions(hashes: List<String>): List<FullApiTransaction> {
            requests += hashes
            if (hashes.any { it in transactionErrorHashes }) {
                throw IllegalStateException("api failed")
            }
            return hashes.mapNotNull(transactionsByHash::get)
        }

        override fun blockHashes(heights: List<Int>): Map<Int, String> {
            blockHashRequests += heights
            blockHashThreadNames += Thread.currentThread().name
            blockHashError?.let { throw it }
            return heights.associateWith { blockHashesByHeight.getValue(it) }
        }

        override fun lastBlockHeader(): BlockHeaderItem {
            error("Not implemented")
        }

        override fun broadcastTransaction(rawTransactionHex: String): JsonValue {
            return Json.value("")
        }
    }

    private fun fullApiTransaction(hash: String, blockId: Int? = null): FullApiTransaction {
        return FullApiTransaction(
            transaction = ApiTransaction(hash = hash, blockId = blockId, date = "", time = "", fee = 0),
            inputs = emptyList<ApiInput>(),
            outputs = emptyList<ApiOutput>()
        )
    }

    private companion object {
        const val MINIMUM_PENDING_AGE_SECONDS = 600L
        const val BLOCK_HASH = "000000000000000000000000000000000000000000000000000000000000007b"
        const val BLOCK_HASH_DISPATCHER_THREAD = "block-hash-dispatcher"
    }
}

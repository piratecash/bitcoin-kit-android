package io.horizontalsystems.bitcoincore.transactions

import io.horizontalsystems.bitcoincore.apisync.blockchair.Api
import io.horizontalsystems.bitcoincore.blocks.IBlockchainDataListener
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.extensions.toReversedByteArray
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.models.BlockHash
import io.horizontalsystems.bitcoincore.models.Transaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

interface PendingTransactionStatusProvider {
    suspend fun transactionStatuses(hashes: List<String>): PendingTransactionLookupResult
}

data class PendingTransactionLookupResult(
    val checkedHashes: Set<String>,
    val statuses: List<PendingTransactionStatus>
)

data class PendingTransactionStatus(
    val hash: String,
    val blockHash: BlockHash?
)

class BlockchairPendingTransactionStatusProvider(
    private val api: Api,
    private val requestDelayMillis: Long = REQUEST_DELAY_MILLIS,
    private val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PendingTransactionStatusProvider {
    override suspend fun transactionStatuses(hashes: List<String>): PendingTransactionLookupResult {
        val transactions = mutableListOf<Pair<String, Int?>>()
        val checkedHashes = mutableSetOf<String>()

        for ((index, hash) in hashes.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                api.getTransactions(listOf(hash)).firstOrNull()?.let {
                    // Blockchair uses -1 for mempool transactions; only positive heights are real blocks.
                    transactions += hash to it.transaction.blockId?.takeIf { blockId -> blockId > 0 }
                }
                checkedHashes += hash
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                break
            }
            if (requestDelayMillis > 0 && index < hashes.lastIndex) {
                delay(requestDelayMillis)
            }
        }

        val blockHashes = blockHashes(transactions.mapNotNull { it.second }.distinct())

        return PendingTransactionLookupResult(
            checkedHashes = checkedHashes,
            statuses = transactions.map { (hash, blockHeight) ->
                PendingTransactionStatus(
                    hash = hash,
                    blockHash = blockHeight?.let { height ->
                        blockHashes[height]?.let { BlockHash(it.toReversedByteArray(), height) }
                    }
                )
            }
        )
    }

    private suspend fun blockHashes(blockHeights: List<Int>): Map<Int, String> {
        if (blockHeights.isEmpty()) {
            return emptyMap()
        }

        return try {
            currentCoroutineContext().ensureActive()
            withContext(blockingDispatcher) {
                currentCoroutineContext().ensureActive()
                api.blockHashes(blockHeights)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            emptyMap()
        }
    }

    companion object {
        private const val REQUEST_DELAY_MILLIS = 2_000L

        private val supportedChainIds = setOf(
            "bitcoin",
            "litecoin",
            "bitcoin-cash",
            "dash",
            "dogecoin"
        )

        fun create(api: Api, chainId: String): PendingTransactionStatusProvider? {
            return if (chainId in supportedChainIds) {
                BlockchairPendingTransactionStatusProvider(api)
            } else {
                null
            }
        }
    }
}

class PendingTransactionReconciler(
    private val storage: IStorage,
    private val statusProvider: PendingTransactionStatusProvider?,
    private val dataListener: IBlockchainDataListener,
    private val invalidateOutgoing: (Transaction) -> Unit,
    private val logTag: String,
    private val coroutineDispatcher: CoroutineDispatcher,
    private val minimumPendingAgeSeconds: Long = DEFAULT_MINIMUM_PENDING_AGE_SECONDS
) {
    private var coroutineScope = createCoroutineScope()
    private val running = AtomicBoolean(false)
    private var active = false

    var onConfirmedBlocksFound: (() -> Unit)? = null

    private fun createCoroutineScope(): CoroutineScope {
        return CoroutineScope(
            SupervisorJob() + coroutineDispatcher + CoroutineExceptionHandler { _, ex ->
                Timber.tag(logTag).d(ex)
            }
        )
    }

    fun reconcileAsync() {
        val job = synchronized(this) {
            if (!active || !running.compareAndSet(false, true)) {
                return
            }
            val scope = ensureScope()
            scope.launch {
                reconcile()
            }
        }
        job.invokeOnCompletion {
            running.set(false)
        }
    }

    suspend fun reconcile(nowSeconds: Long = System.currentTimeMillis() / 1000) {
        val statusProvider = statusProvider ?: return
        val pendingTransactions = storage.getRelayedPendingTransactions(Transaction.Status.RELAYED)

        if (pendingTransactions.isEmpty()) {
            return
        }

        val (malformedTransactions, lookupTransactions) =
            pendingTransactions.partition(::isMalformedOutgoingTransaction)
        deleteMalformedTransactions(malformedTransactions)

        if (lookupTransactions.isEmpty()) {
            return
        }

        try {
            val transactionsByHash = lookupTransactions.associateBy { it.hash.toReversedHex() }
            val lookupResult = statusProvider.transactionStatuses(transactionsByHash.keys.toList())
            val existingHashes = lookupResult.statuses.map { it.hash }.toSet()
            addConfirmedBlockHashes(lookupResult.statuses.mapNotNull { it.blockHash })

            val droppedTransactions = transactionsByHash
                .filterKeys { it in lookupResult.checkedHashes && it !in existingHashes }
                .values
                .filter { it.isStale(nowSeconds) }
                .toList()

            handleDroppedTransactions(droppedTransactions)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Timber.tag(logTag).d(error, "Failed to reconcile pending transactions")
        }
    }

    private fun isMalformedOutgoingTransaction(transaction: Transaction): Boolean {
        if (!transaction.isOutgoing) {
            return false
        }

        return storage.getTransactionInputs(transaction).isEmpty()
    }

    private fun addConfirmedBlockHashes(blockHashes: List<BlockHash>) {
        val newBlockHashes = blockHashes
            .distinctBy { it.headerHash.toHexString() }
            .filter { !storage.hasBlockHash(it.headerHash) && storage.getBlock(it.headerHash) == null }
        if (newBlockHashes.isEmpty()) {
            return
        }

        storage.addBlockHashes(newBlockHashes)
        onConfirmedBlocksFound?.invoke()
    }

    private fun Transaction.isStale(nowSeconds: Long): Boolean {
        return nowSeconds - timestamp >= minimumPendingAgeSeconds
    }

    private fun handleDroppedTransactions(transactions: List<Transaction>) {
        if (transactions.isEmpty()) {
            return
        }

        val (outgoing, incoming) = transactions.partition { it.isOutgoing }
        outgoing.forEach(invalidateOutgoing)
        deleteIncomingTransactions(incoming)
    }

    private fun deleteMalformedTransactions(transactions: List<Transaction>) {
        if (transactions.isEmpty()) {
            return
        }

        Timber.tag(logTag).d(
            "Deleting malformed relayed outgoing transactions without inputs: " +
                transactions.joinToString { it.hash.toReversedHex() }
        )
        val deletedTransactions = storage.deleteRelayedPendingTransactions(transactions)
        notifyDeletedTransactions(deletedTransactions)
    }

    private fun deleteIncomingTransactions(transactions: List<Transaction>) {
        if (transactions.isEmpty()) {
            return
        }

        val deletedTransactions = storage.deleteRelayedPendingTransactions(transactions)
        notifyDeletedTransactions(deletedTransactions)
    }

    private fun notifyDeletedTransactions(deletedTransactions: List<Transaction>) {
        if (deletedTransactions.isNotEmpty()) {
            dataListener.onTransactionsDelete(deletedTransactions.map { it.hash.toReversedHex() })
        }
    }

    @Synchronized
    private fun ensureScope(): CoroutineScope {
        if (coroutineScope.coroutineContext[Job]?.isActive != true) {
            coroutineScope = createCoroutineScope()
        }

        return coroutineScope
    }

    @Synchronized
    fun start() {
        active = true
        ensureScope()
    }

    @Synchronized
    fun stop() {
        active = false
        coroutineScope.cancel()
    }

    companion object {
        const val DEFAULT_MINIMUM_PENDING_AGE_SECONDS = 24 * 60 * 60L
    }
}

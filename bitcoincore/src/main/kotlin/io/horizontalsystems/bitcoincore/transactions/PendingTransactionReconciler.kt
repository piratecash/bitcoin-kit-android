package io.horizontalsystems.bitcoincore.transactions

import co.touchlab.kermit.Logger
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
            return if (isChainSupported(chainId)) {
                BlockchairPendingTransactionStatusProvider(api)
            } else {
                null
            }
        }

        // Blockchair's proxy is known to be unreliable for tx-by-hash lookups on chains outside
        // this list (e.g. eCash), so callers relying on getTransactions() must check this first.
        fun isChainSupported(chainId: String): Boolean = chainId in supportedChainIds
    }
}

class PendingTransactionReconciler(
    private val storage: IStorage,
    private val statusProvider: PendingTransactionStatusProvider?,
    private val dataListener: IBlockchainDataListener,
    private val outgoingInvalidator: OutgoingTransactionInvalidator,
    private val logTag: String,
    private val coroutineDispatcher: CoroutineDispatcher,
    private val minimumPendingAgeSeconds: Long = DEFAULT_MINIMUM_PENDING_AGE_SECONDS,
    private val minimumNewPendingAgeSeconds: Long = DEFAULT_MINIMUM_NEW_PENDING_AGE_SECONDS,
) {
    private val log = Logger.withTag(logTag)

    private var coroutineScope = createCoroutineScope()
    private val running = AtomicBoolean(false)
    private var active = false

    var onConfirmedBlocksFound: (() -> Unit)? = null

    private fun createCoroutineScope(): CoroutineScope {
        return CoroutineScope(
            SupervisorJob() + coroutineDispatcher + CoroutineExceptionHandler { _, ex ->
                log.d(ex) { "" }
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
        val pendingTransactions = storage.getRelayedPendingTransactions(Transaction.Status.RELAYED) +
            storage.getRelayedPendingTransactions(Transaction.Status.NEW)

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
            log.d(error) { "Failed to reconcile pending transactions" }
        }
    }

    // Restricted to RELAYED: a malformed NEW transaction (never broadcast) instead ages out through
    // the normal NEW-expiry path below, which is short enough (minimumNewPendingAgeSeconds) on its own.
    private fun isMalformedOutgoingTransaction(transaction: Transaction): Boolean {
        if (!transaction.isOutgoing || transaction.status != Transaction.Status.RELAYED) {
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
        val ageThreshold = if (status == Transaction.Status.NEW) {
            minimumNewPendingAgeSeconds
        } else {
            minimumPendingAgeSeconds
        }
        return nowSeconds - timestamp >= ageThreshold
    }

    private fun handleDroppedTransactions(transactions: List<Transaction>) {
        if (transactions.isEmpty()) {
            return
        }

        val (outgoing, incoming) = transactions.partition { it.isOutgoing }
        val (newOutgoing, relayedOutgoing) = outgoing.partition { it.status == Transaction.Status.NEW }

        // NEW outgoing transactions never reached the network, so their inputs are freed by
        // deleting them outright instead of invalidating (which would mark inputs failedToSpend).
        deletePendingTransactions(newOutgoing)
        relayedOutgoing.forEach(outgoingInvalidator::invalidate)
        deletePendingTransactions(incoming)
    }

    private fun deleteMalformedTransactions(transactions: List<Transaction>) {
        if (transactions.isEmpty()) {
            return
        }

        log.d {
            "Deleting malformed relayed outgoing transactions without inputs: " +
            transactions.joinToString { it.hash.toReversedHex() }
        }
        deletePendingTransactions(transactions)
    }

    private fun deletePendingTransactions(transactions: List<Transaction>) {
        if (transactions.isEmpty()) {
            return
        }

        transactions.groupBy { it.status }.forEach { (status, group) ->
            val deletedTransactions = if (status == Transaction.Status.NEW) {
                // A transaction currently having its bytes handed to the network (API broadcast or
                // P2P getdata serve) already has a SentTransaction record (see
                // TransactionSender.recordBroadcastAttempt), so storage skips deleting it even
                // though it is still NEW - deleting it out from under a real send would clobber it.
                storage.deleteNewExpiredTransactions(group)
            } else {
                storage.deleteRelayedPendingTransactions(group, status)
            }
            notifyDeletedTransactions(deletedTransactions)
        }
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
        const val DEFAULT_MINIMUM_NEW_PENDING_AGE_SECONDS = 60 * 60L
    }
}

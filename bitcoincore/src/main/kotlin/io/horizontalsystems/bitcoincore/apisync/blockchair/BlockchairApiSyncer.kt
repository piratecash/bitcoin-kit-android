package io.horizontalsystems.bitcoincore.apisync.blockchair

import io.horizontalsystems.bitcoincore.blocks.Blockchain
import io.horizontalsystems.bitcoincore.core.IApiSyncer
import io.horizontalsystems.bitcoincore.core.IApiSyncerListener
import io.horizontalsystems.bitcoincore.core.IApiTransactionProvider
import io.horizontalsystems.bitcoincore.core.IPublicKeyManager
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toReversedByteArray
import io.horizontalsystems.bitcoincore.managers.ApiSyncStateManager
import io.horizontalsystems.bitcoincore.managers.IRestoreKeyConverter
import io.horizontalsystems.bitcoincore.models.BlockHash
import io.horizontalsystems.bitcoincore.models.BlockHashPublicKey
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.random.Random

class BlockchairApiSyncer(
    private val storage: IStorage,
    private val restoreKeyConverter: IRestoreKeyConverter,
    private val transactionProvider: IApiTransactionProvider,
    private val lastBlockProvider: LastBlockProvider,
    private val publicKeyManager: IPublicKeyManager,
    private val blockchain: Blockchain,
    private val apiSyncStateManager: ApiSyncStateManager,
    private val retryScheduler: Scheduler = Schedulers.io(),
) : IApiSyncer {

    private val logger = Logger.getLogger("BlockchairApiSyncer")
    private val disposables = CompositeDisposable()

    // The scan body is blocking and terminate() cannot interrupt it, so only the run that still
    // owns this token may notify the listener — a late callback would restart a stopped sync.
    @Volatile
    private var currentRun: SyncRun? = null

    override var listener: IApiSyncerListener? = null

    override val willSync: Boolean = true

    override fun sync() {
        val run = SyncRun()
        currentRun = run

        scanSingle(run)
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .subscribe({}, {
                handleError(run, it)
            }).let {
                disposables.add(it)
            }
    }

    override fun terminate() {
        currentRun = null
        disposables.clear()
    }

    private fun isCurrent(run: SyncRun) = currentRun === run

    private fun listenerOf(run: SyncRun) = listener.takeIf { isCurrent(run) }

    private fun handleError(run: SyncRun, error: Throwable) {
        logger.severe("Error: ${error.message}")

        if (!isCurrent(run)) return

        if (run.attempt >= RETRY_DELAYS_MS.size) {
            listenerOf(run)?.onSyncFailed(error)
            return
        }

        // Jitter keeps the coins apart: one refresh starts every syncer at the same instant and the
        // shared HTTP/2 connection answers REFUSED_STREAM to the whole burst.
        val baseDelay = RETRY_DELAYS_MS[run.attempt]
        val delay = baseDelay + Random.nextLong(baseDelay / 2)
        run.attempt++

        logger.warning("Retrying sync ${run.attempt}/${RETRY_DELAYS_MS.size} in ${delay}ms")

        disposables.add(
            Single.timer(delay, TimeUnit.MILLISECONDS, retryScheduler)
                .flatMap { scanSingle(run) }
                .subscribe({}, { handleError(run, it) })
        )
    }

    private fun fetchLastBlock() {
        val blockHeaderItem = lastBlockProvider.lastBlockHeader()
        val header = BlockHeader(
            version = 0,
            hash = blockHeaderItem.hash,
            previousBlockHeaderHash = byteArrayOf(),
            merkleRoot = byteArrayOf(),
            timestamp = blockHeaderItem.timestamp,
            bits = -1,
            nonce = 0
        )

        blockchain.insertLastBlock(header, blockHeaderItem.height)
    }

    private fun scanSingle(run: SyncRun): Single<Unit> = Single.create { emitter ->
        try {
            val allKeys = storage.getPublicKeys()
            val stopHeight = run.stopHeight
                ?: storage.downloadedTransactionsBestBlockHeight().also { run.stopHeight = it }
            fetchRecursive(run, allKeys, allKeys, stopHeight)

            if (isCurrent(run)) {
                fetchLastBlock()
            }

            // fetchLastBlock() is another blocking request, so the token is rechecked after it:
            // a late onSyncSuccess() would restart the peer group the pause had just stopped.
            if (isCurrent(run)) {
                apiSyncStateManager.restored = true
                listener?.onSyncSuccess()
            }

            if (!emitter.isDisposed) {
                emitter.onSuccess(Unit)
            }
        } catch (error: Throwable) {
            if (!emitter.isDisposed) {
                emitter.onError(error)
            }
        }
    }

    private fun fetchRecursive(
        run: SyncRun,
        keys: List<PublicKey>,
        allKeys: List<PublicKey>,
        stopHeight: Int
    ) {
        // Each recursion starts a new blocking request, so a terminate() during the previous
        // round's storage writes or fillGap() must stop it here.
        if (!isCurrent(run)) return

        val publicKeyMap = mutableMapOf<String, PublicKey>()
        val addresses = mutableListOf<String>()

        for (key in keys) {
            val restoreKeys = restoreKeyConverter.keysForApiRestore(key)
            for (address in restoreKeys) {
                addresses.add(address)
                publicKeyMap[address] = key
            }
        }

        // Address derivation above is not instant, so re-check before firing the request rather
        // than starting one the pause has already cancelled.
        if (!isCurrent(run)) return

        val transactionItems = transactionProvider.transactions(addresses, stopHeight)
        // The request above is not interruptible, so this is the first point where a terminate()
        // can take effect — before any storage write and before the next round of requests.
        if (!isCurrent(run)) return

        val blockHashes = mutableListOf<BlockHash>()
        val blockHashPublicKeys = mutableListOf<BlockHashPublicKey>()

        for (transactionItem in transactionItems) {
            val hash = transactionItem.blockHash.toReversedByteArray()

            if (blockHashes.none { it.headerHash.contentEquals(hash) }) {
                BlockHash(hash, transactionItem.blockHeight).also {
                    blockHashes.add(it)
                }
            }

            transactionItem.addressItems.forEach { addressItem ->
                val publicKey = publicKeyMap[addressItem.address] ?: publicKeyMap[addressItem.script]
                if (publicKey != null) {
                    blockHashPublicKeys.add(BlockHashPublicKey(hash, publicKey.path))
                }
            }
        }

        storage.addBlockHashes(blockHashes)
        storage.addBockHashPublicKeys(blockHashPublicKeys)
        listenerOf(run)?.onTransactionsFound(transactionItems.size)

        publicKeyManager.fillGap()

        val _allKeys = storage.getPublicKeys()
        val newKeys = _allKeys.minus(allKeys.toSet())

        if (newKeys.isNotEmpty()) {
            fetchRecursive(run, newKeys, _allKeys, stopHeight)
        }
    }

    // Retry state belongs to the run, not to the syncer: a stale run that terminate() could not
    // interrupt must never write into the state of the run that replaced it.
    private class SyncRun {
        @Volatile
        var attempt = 0

        // Frozen for the whole run: a retry must not raise the cutoff past the block hashes the
        // failed attempt itself discovered, or addresses derived later lose their history below it.
        @Volatile
        var stopHeight: Int? = null
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(5_000, 20_000, 60_000)
    }
}

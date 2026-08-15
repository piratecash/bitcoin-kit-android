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
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import java.util.logging.Logger

class BlockchairApiSyncer(
    private val storage: IStorage,
    private val restoreKeyConverter: IRestoreKeyConverter,
    private val transactionProvider: IApiTransactionProvider,
    private val lastBlockProvider: LastBlockProvider,
    private val publicKeyManager: IPublicKeyManager,
    private val blockchain: Blockchain,
    private val apiSyncStateManager: ApiSyncStateManager,
) : IApiSyncer {

    private val logger = Logger.getLogger("BlockchairApiSyncer")
    private val disposables = CompositeDisposable()

    // The scan body is blocking and terminate() cannot interrupt it, so only the run that still
    // owns this token may notify the listener — a late callback would restart a stopped sync.
    @Volatile
    private var currentRun: Any? = null

    override var listener: IApiSyncerListener? = null

    override val willSync: Boolean = true

    override fun sync() {
        val run = Any()
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

    private fun isCurrent(run: Any) = currentRun === run

    private fun listenerOf(run: Any) = listener.takeIf { isCurrent(run) }

    private fun handleError(run: Any, error: Throwable) {
        logger.severe("Error: ${error.message}")
        listenerOf(run)?.onSyncFailed(error)
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

    private fun scanSingle(run: Any): Single<Unit> = Single.create { emitter ->
        try {
            val allKeys = storage.getPublicKeys()
            val stopHeight = storage.downloadedTransactionsBestBlockHeight()
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
        run: Any,
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
}

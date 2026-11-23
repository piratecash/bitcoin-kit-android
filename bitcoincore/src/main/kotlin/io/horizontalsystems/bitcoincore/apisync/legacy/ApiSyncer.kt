package io.horizontalsystems.bitcoincore.apisync.legacy

import io.horizontalsystems.bitcoincore.core.IApiSyncer
import io.horizontalsystems.bitcoincore.core.IApiSyncerListener
import io.horizontalsystems.bitcoincore.core.IPublicKeyManager
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.managers.ApiSyncStateManager
import io.horizontalsystems.bitcoincore.models.BlockHash
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import java.util.logging.Logger

class ApiSyncer(
    private val storage: IStorage,
    private val blockHashDiscovery: BlockHashDiscoveryBatch,
    private val publicKeyManager: IPublicKeyManager,
    private val multiAccountPublicKeyFetcher: IMultiAccountPublicKeyFetcher?,
    private val apiSyncStateManager: ApiSyncStateManager
) : IApiSyncer {

    override val willSync: Boolean
        get() = !apiSyncStateManager.restored

    override var listener: IApiSyncerListener? = null

    private val logger = Logger.getLogger("ApiSyncer")
    private val disposables = CompositeDisposable()

    // The multi-account path re-subscribes from its own callback, and clear() leaves the container
    // reusable, so only the run that still owns this token may continue or notify the listener.
    @Volatile
    private var currentRun: Any? = null

    override fun terminate() {
        currentRun = null
        disposables.clear()
    }

    override fun sync() {
        val run = Any()
        currentRun = run

        sync(run)
    }

    private fun isCurrent(run: Any) = currentRun === run

    private fun sync(run: Any) {
        if (!isCurrent(run)) return

        val disposable = blockHashDiscovery.discoverBlockHashes()
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .subscribe(
                { (publicKeys, blockHashes) ->
                    val sortedUniqueBlockHashes = blockHashes.distinctBy { it.height }.sortedBy { it.height }

                    handle(run, publicKeys, sortedUniqueBlockHashes)
                },
                {
                    handleError(run, it)
                })

        disposables.add(disposable)
        // A terminate() between the check above and this add() clears an empty container, so the
        // subscription would survive and keep scanning; re-check and dispose it ourselves.
        if (!isCurrent(run)) disposable.dispose()
    }

    private fun handle(run: Any, keys: List<PublicKey>, blockHashes: List<BlockHash>) {
        if (!isCurrent(run)) return

        publicKeyManager.addKeys(keys)

        if (multiAccountPublicKeyFetcher != null) {
            if (blockHashes.isNotEmpty()) {
                storage.addBlockHashes(blockHashes)
                // The storage writes above take long enough for a terminate() to land, and the
                // account counter is shared with the next run: bumping it here would shift a scan
                // that has already started deriving keys, mixing two accounts in one batch.
                if (!isCurrent(run)) return
                multiAccountPublicKeyFetcher.increaseAccount()
                sync(run)
            } else {
                handleSuccess(run)
            }
        } else {
            storage.addBlockHashes(blockHashes)
            handleSuccess(run)
        }
    }

    private fun handleSuccess(run: Any) {
        if (!isCurrent(run)) return

        apiSyncStateManager.restored = true
        listener?.onSyncSuccess()
    }

    private fun handleError(run: Any, error: Throwable) {
        logger.severe("Initial Sync Error: ${error.message}")

        if (!isCurrent(run)) return

        listener?.onSyncFailed(error)
    }
}

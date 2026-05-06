package io.horizontalsystems.litecoinkit.mweb

import android.content.Context
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressCodec
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressPool
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClient
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClientFactory
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonConfig
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonStatus
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebdAndroidDaemonClientFactory
import io.horizontalsystems.litecoinkit.mweb.storage.MwebDatabase
import io.horizontalsystems.litecoinkit.mweb.storage.MwebRoomStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArraySet

internal class LitecoinMwebEngine(
    context: Context,
    seed: ByteArray,
    walletId: String,
    private val dispatcherProvider: MwebDispatcherProvider,
    private val networkType: LitecoinKit.NetworkType = LitecoinKit.NetworkType.MainNet,
    private val restorePoint: MwebRestorePoint = MwebRestorePoint.Activation,
    private val peerAddress: String? = null,
    private val daemonClientFactory: MwebDaemonClientFactory = MwebdAndroidDaemonClientFactory,
    private val spentPollIntervalMillis: Long = SPENT_POLL_INTERVAL_MILLIS,
) {
    interface Listener {
        fun onMwebBalanceUpdate(balance: MwebBalance) = Unit
        fun onMwebSyncStateUpdate(state: MwebSyncState) = Unit
        fun onMwebUtxosUpdate(utxos: List<MwebUtxo>) = Unit
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    var balance: MwebBalance = MwebBalance(confirmed = 0, unconfirmed = 0)
        private set

    @Volatile
    var syncState: MwebSyncState = MwebSyncState(
        blockHeaderHeight = 0,
        mwebHeaderHeight = 0,
        mwebUtxosHeight = 0,
    )
        private set

    private val appContext = context.applicationContext
    private val accountKeys = MwebKeyManager(seed).accountKeys()
    private val restoreHeight = MwebRestorePolicy(MwebNetworkPolicy.network(networkType)).resolve(restorePoint)
    private val daemonDataDir = MwebFiles.daemonDataDir(appContext, networkType, walletId)
    private val addressCodec = MwebAddressCodec(networkType)
    private val storage = MwebRoomStorage(
        MwebDatabase.getInstance(appContext, MwebFiles.databaseName(networkType, walletId))
    )
    private val coroutineScope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)
    private val stateMutex = Mutex()
    @Volatile
    private var daemonClient: MwebDaemonClient? = null
    private var refreshJob: Job? = null
    @Volatile
    private var nativeVersion: String = ""
    @Volatile
    private var utxos: List<MwebUtxo> = emptyList()
    @Volatile
    private var started = false
    private val utxoSynchronizer = MwebUtxoSynchronizer(
        storage = storage,
        coroutineScope = coroutineScope,
        stateMutex = stateMutex,
        restoreHeight = restoreHeight,
        spentPollIntervalMillis = spentPollIntervalMillis,
        syncStateProvider = { syncState },
        activeClientProvider = { daemonClient },
        isActiveClient = { client -> started && daemonClient === client },
        onNativeUnavailable = { started = false },
        onSnapshot = { snapshot -> applyUtxoSnapshot(snapshot) },
    )

    init {
        runOnIoBlocking {
            storage.syncState()?.let { syncState = it }
            applyUtxoSnapshot(utxoSynchronizer.loadSnapshot(), notify = false)
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Starts mwebd and MWEB storage synchronization.
     *
     * This is a synchronous wrapper over blocking native/storage work; do not call from
     * Android main thread.
     */
    fun start() {
        runOnIoBlocking {
            stateMutex.withLock {
                if (started) return@withLock

                val client = daemonClient ?: createDaemonClient()
                val status = MwebDaemonErrorMapper.map {
                    client.start(MwebDaemonClient.DEFAULT_STATUS_TIMEOUT_MILLIS)
                }
                daemonClient = client
                started = true
                addressPool().addresses(MwebAddressPool.CHANGE_INDEX, MwebAddressPool.FIRST_RECEIVE_INDEX)
                utxoSynchronizer.refreshSpentOutputs(client)
                utxoSynchronizer.startSpentPolling(client)
                utxoSynchronizer.startUtxoStream(client)
                applyStatus(status)
            }
        }
    }

    /**
     * Stops mwebd and background MWEB jobs.
     *
     * This is a synchronous wrapper over blocking native/storage work; do not call from
     * Android main thread.
     */
    fun stop() {
        runOnIoBlocking {
            stateMutex.withLock {
                stopLocked()
            }
        }
    }

    private fun stopLocked() {
        refreshJob?.cancel()
        refreshJob = null
        utxoSynchronizer.stop()
        coroutineScope.coroutineContext.cancelChildren()
        if (!started) return

        val client = daemonClient
        started = false
        client?.let {
            MwebDaemonErrorMapper.map { client.stop() }
        }
    }

    /**
     * Releases daemon and database resources.
     *
     * This is a synchronous wrapper over blocking native/storage work; do not call from
     * Android main thread.
     */
    fun dispose() {
        stop()
        runOnIoBlocking {
            storage.close()
        }
    }

    /**
     * Restarts lightweight status/UTXO collection without deleting the MWEB database
     * or daemon data directory. Calling it before `start()` is a no-op.
     *
     * This is a synchronous wrapper over blocking storage work; do not call from Android
     * main thread.
     */
    fun refresh() {
        runOnIoBlocking {
            refreshJob?.cancel()
            refreshJob = coroutineScope.launch {
                stateMutex.withLock {
                    val client = daemonClient?.takeIf { started } ?: return@withLock
                    val status = MwebDaemonErrorMapper.mapSuspend {
                        client.status(MwebDaemonClient.DEFAULT_STATUS_TIMEOUT_MILLIS)
                    }
                    utxoSynchronizer.refresh(client)
                    applyStatus(status)
                }
            }
        }
    }

    /**
     * Returns the next receive MWEB address.
     *
     * This is a synchronous wrapper over blocking native/storage work; do not call from
     * Android main thread.
     */
    fun receiveAddress(): String {
        return runOnIoBlocking {
            stateMutex.withLock {
                MwebDaemonErrorMapper.map { addressPool().receiveAddress() }
            }
        }
    }

    /**
     * Returns MWEB addresses for the inclusive index range.
     *
     * This is a synchronous wrapper over blocking native/storage work; do not call from
     * Android main thread.
     */
    fun addresses(fromIndex: Int, toIndex: Int): List<String> {
        return runOnIoBlocking {
            stateMutex.withLock {
                MwebDaemonErrorMapper.map { addressPool().addresses(fromIndex, toIndex) }
            }
        }
    }

    fun isMwebAddress(address: String): Boolean {
        return addressCodec.isValid(address)
    }

    /**
     * Builds an MWEB fee/selection preview.
     *
     * This is a synchronous wrapper over blocking native/storage work; do not call from
     * Android main thread.
     */
    fun sendInfo(
        request: MwebSendRequest,
        publicOptions: MwebPublicSendOptions,
        publicTransactionBridge: MwebPublicTransactionBridge? = null,
    ): MwebSendInfo {
        return runOnIoBlocking {
            stateMutex.withLock {
                val prepared = prepareTransaction(
                    request = request,
                    publicOptions = publicOptions,
                    client = requireStartedClient(),
                    publicTransactionBridge = publicTransactionBridge,
                )
                prepared.sendInfo()
            }
        }
    }

    suspend fun send(
        request: MwebSendRequest,
        publicOptions: MwebPublicSendOptions,
        publicTransactionBridge: MwebPublicTransactionBridge? = null,
    ): MwebSendResult = withContext(dispatcherProvider.io) {
        stateMutex.withLock {
            val client = requireStartedClient()
            val prepared = prepareTransaction(
                request = request,
                publicOptions = publicOptions,
                client = client,
                publicTransactionBridge = publicTransactionBridge,
            )
            val createResult = MwebDaemonErrorMapper.mapSuspend {
                client.create(prepared.rawTemplate, request.feeRate, dryRun = false)
            }
            val rawTransaction = signPublicInputs(
                rawTransaction = createResult.rawTransaction,
                selectedPublicUtxos = prepared.selectedPublicUtxos,
                publicTransactionBridge = publicTransactionBridge,
            )
            val transactionHash = MwebDaemonErrorMapper.mapSuspend {
                client.broadcast(rawTransaction)
            }
            val result = MwebSendResult(
                canonicalTransactionHash = transactionHash,
                rawTransaction = rawTransaction,
                outputIds = createResult.outputIds,
            )
            storage.savePendingTransaction(
                MwebPendingTransaction(
                    rawTransaction = result.rawTransaction,
                    createdOutputIds = result.outputIds,
                    canonicalTransactionHash = result.canonicalTransactionHash,
                    timestamp = System.currentTimeMillis(),
                )
            )
            utxoSynchronizer.markSpent(prepared.selectedMwebUtxos.map { it.outputId })
            result
        }
    }

    /**
     * Returns a debug snapshot without secrets or raw transactions.
     *
     * This is a synchronous wrapper over blocking storage work; do not call from Android
     * main thread.
     */
    fun debugInfo(): MwebDebugInfo {
        return runOnIoBlocking {
            stateMutex.withLock {
                MwebDebugInfo(
                    state = syncState,
                    peerAddress = peerAddress,
                    addressPoolSize = storage.addresses().size,
                    unspentUtxoCount = utxos.count { !it.spent },
                    pendingTransactionCount = storage.pendingTransactions().size,
                    nativeVersion = nativeVersion,
                )
            }
        }
    }

    /**
     * Returns the locally cached MWEB UTXO list.
     *
     * This is a synchronous wrapper over blocking storage/state work; do not call from
     * Android main thread.
     */
    fun mwebUtxos(): List<MwebUtxo> {
        return runOnIoBlocking {
            stateMutex.withLock { utxos }
        }
    }

    /**
     * Returns locally pending MWEB transactions.
     *
     * This is a synchronous wrapper over blocking storage work; do not call from Android
     * main thread.
     */
    fun pendingTransactions(): List<MwebPendingTransaction> {
        return runOnIoBlocking {
            stateMutex.withLock { storage.pendingTransactions() }
        }
    }

    private fun createDaemonClient(): MwebDaemonClient {
        return MwebDaemonErrorMapper.map {
            daemonClientFactory.create(
                MwebDaemonConfig(
                    networkType = networkType,
                    accountKeys = accountKeys,
                    peerAddress = peerAddress,
                    dataDir = daemonDataDir,
                    restoreHeight = restoreHeight,
                )
            )
        }
    }

    private fun addressPool(): MwebAddressPool {
        return MwebAddressPool(addressCodec, requireStartedClient(), storage)
    }

    private fun prepareTransaction(
        request: MwebSendRequest,
        publicOptions: MwebPublicSendOptions,
        client: MwebDaemonClient,
        publicTransactionBridge: MwebPublicTransactionBridge?,
    ): PreparedMwebTransaction {
        val transactionPreparer = MwebTransactionPreparer(
            addressCodec = addressCodec,
            publicTransactionBridge = publicTransactionBridge,
            changeAddressProvider = { addressPool().changeAddress() },
            syncStateProvider = { syncState },
            utxosProvider = { utxos },
        )
        return transactionPreparer.prepare(request, publicOptions) { rawTemplate, feeRate ->
            MwebDaemonErrorMapper.map {
                client.create(rawTemplate, feeRate, dryRun = true).rawTransaction
            }
        }
    }

    private suspend fun signPublicInputs(
        rawTransaction: ByteArray,
        selectedPublicUtxos: List<UnspentOutput>,
        publicTransactionBridge: MwebPublicTransactionBridge?,
    ): ByteArray {
        if (selectedPublicUtxos.isEmpty()) return rawTransaction

        val bridge = requirePublicBridge(publicTransactionBridge)
        val signedTransaction = bridge.sign(rawTransaction, selectedPublicUtxos)
        return bridge.serialize(bridge.processCreated(signedTransaction))
    }

    private fun requirePublicBridge(publicTransactionBridge: MwebPublicTransactionBridge?): MwebPublicTransactionBridge {
        return publicTransactionBridge ?: throw MwebError.NativeUnavailable()
    }

    private fun requireStartedClient(): MwebDaemonClient {
        if (!started) {
            throw MwebError.SyncFailure()
        }
        return daemonClient ?: throw MwebError.NativeUnavailable()
    }

    private fun applyStatus(status: MwebDaemonStatus) {
        syncState = status.syncState
        nativeVersion = status.nativeVersion
        storage.saveSyncState(syncState)
        coroutineScope.launch(dispatcherProvider.callback) {
            notifyListeners { listener -> listener.onMwebSyncStateUpdate(syncState) }
        }
    }

    private fun applyUtxoSnapshot(snapshot: MwebUtxoSnapshot, notify: Boolean = true) {
        utxos = snapshot.utxos
        balance = snapshot.balance
        if (!notify) return

        coroutineScope.launch(dispatcherProvider.callback) {
            notifyListeners { listener ->
                listener.onMwebUtxosUpdate(utxos)
                listener.onMwebBalanceUpdate(balance)
            }
        }
    }

    private fun notifyListeners(action: (Listener) -> Unit) {
        listeners.forEach(action)
    }

    private fun <T> runOnIoBlocking(block: suspend () -> T): T {
        return runBlocking(dispatcherProvider.io) { block() }
    }

    companion object {
        private const val SPENT_POLL_INTERVAL_MILLIS = 60_000L

        fun clear(context: Context, networkType: LitecoinKit.NetworkType, walletId: String) {
            MwebFiles.clear(context.applicationContext, networkType, walletId)
        }
    }
}

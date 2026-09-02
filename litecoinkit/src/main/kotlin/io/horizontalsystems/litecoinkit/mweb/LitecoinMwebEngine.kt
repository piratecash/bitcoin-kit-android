package io.horizontalsystems.litecoinkit.mweb

import co.touchlab.kermit.Logger
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressCodec
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressPool
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClient
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClientFactory
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonConfig
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonStatus
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebdKmpDaemonClientFactory
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebRestoreCheckpointProvider
import io.horizontalsystems.litecoinkit.mweb.storage.MwebDatabase
import io.horizontalsystems.litecoinkit.mweb.storage.MwebRoomStorage
import kotlinx.coroutines.CancellationException
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

private const val MWEB_ENGINE_LOG_TAG = "MwebEngine"
private val log = Logger.withTag(MWEB_ENGINE_LOG_TAG)

internal class LitecoinMwebEngine(
    dataDir: String,
    mwebDataDir: String,
    seed: ByteArray,
    walletId: String,
    private val dispatcherProvider: MwebDispatcherProvider,
    private val networkType: LitecoinKit.NetworkType = LitecoinKit.NetworkType.MainNet,
    private val restorePoint: MwebRestorePoint = MwebRestorePoint.Activation,
    private val peerAddress: String? = null,
    private val daemonClientFactory: MwebDaemonClientFactory = MwebdKmpDaemonClientFactory,
    private val spentPollIntervalMillis: Long = SPENT_POLL_INTERVAL_MILLIS,
    private val statusPollIntervalMillis: Long = STATUS_POLL_INTERVAL_MILLIS,
    private val replayCompleteTimeoutMillis: Long = MwebUtxoSynchronizer.DEFAULT_REPLAY_COMPLETE_TIMEOUT_MILLIS,
    private val streamHealthyThresholdMillis: Long = MwebUtxoSynchronizer.DEFAULT_STREAM_HEALTHY_THRESHOLD_MILLIS,
    private val localTransactionTtlMillis: Long = LOCAL_TRANSACTION_TTL_MILLIS,
    private val currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val canonicalTransactionHashProvider: MwebCanonicalTransactionHashProvider =
        MwebExplorerCanonicalTransactionHashProvider.create(networkType),
    private val restoreCheckpointProvider: (LitecoinKit.NetworkType, Int) -> String? =
        MwebRestoreCheckpointProvider::encodedCheckpoint,
    databaseKey: ByteArray? = null,
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

    private val accountKeys = MwebKeyManager(seed).accountKeys()
    private val restoreHeight = MwebRestorePolicy(MwebNetworkPolicy.network(networkType)).resolve(restorePoint)
    private val daemonDataDir = MwebFiles.daemonDataDir(mwebDataDir, networkType, walletId)
    private val addressCodec = MwebAddressCodec(networkType)
    private val storage = MwebRoomStorage(
        MwebDatabase.getInstance(dataDir, MwebFiles.databaseName(networkType, walletId), databaseKey)
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
    private var transactionsCache: List<MwebTransaction>? = null
    @Volatile
    private var started = false
    private val utxoSynchronizer = MwebUtxoSynchronizer(
        storage = storage,
        coroutineScope = coroutineScope,
        stateMutex = stateMutex,
        restoreHeight = restoreHeight,
        spentPollIntervalMillis = spentPollIntervalMillis,
        statusPollIntervalMillis = statusPollIntervalMillis,
        syncStateProvider = { syncState },
        activeClientProvider = { daemonClient },
        isActiveClient = { client -> started && daemonClient === client },
        canonicalTransactionHashProvider = canonicalTransactionHashProvider,
        onNativeUnavailable = { started = false },
        onStatus = { status -> applyStatus(status) },
        onSnapshot = { snapshot -> applyUtxoSnapshot(snapshot) },
        replayCompleteTimeoutMillis = replayCompleteTimeoutMillis,
        streamHealthyThresholdMillis = streamHealthyThresholdMillis,
    )

    init {
        runOnIoBlocking {
            storage.syncState()?.let { syncState = it }
            applyUtxoSnapshot(utxoSynchronizer.loadStoredSnapshot(), notify = false)
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
                applyStatus(status)
                utxoSynchronizer.refresh(client)
                utxoSynchronizer.startStatusPolling(client)
                utxoSynchronizer.startSpentPolling(client)
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
                    utxoSynchronizer.refresh(client, recoverFailure = true)
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
        val (result, publicTransaction) = stateMutex.withLock {
            val signedTransaction = createSignedMwebTransaction(
                request = request,
                publicOptions = publicOptions,
                publicTransactionBridge = publicTransactionBridge,
            )
            val broadcastHash = MwebDaemonErrorMapper.mapSuspend {
                requireStartedClient().broadcast(signedTransaction.rawTransaction)
            }
            val canonicalTransactionHash = when (request) {
                is MwebSendRequest.PublicToMweb -> {
                    signedTransaction.setPublicTransactionHash(broadcastHash)
                    broadcastHash
                }
                is MwebSendRequest.MwebToPublic,
                is MwebSendRequest.MwebToMweb -> null
            }
            val result = MwebSendResult(
                canonicalTransactionHash = canonicalTransactionHash,
                rawTransaction = signedTransaction.rawTransaction,
                outputIds = signedTransaction.outputIds,
            )
            val timestamp = currentTimeMillisProvider()
            val selectedMwebOutputIds = signedTransaction.selectedMwebOutputIds
            storage.saveBroadcastResult(
                pendingTransaction = MwebPendingTransaction(
                    rawTransaction = result.rawTransaction,
                    createdOutputIds = result.outputIds,
                    canonicalTransactionHash = result.canonicalTransactionHash,
                    timestamp = timestamp,
                ),
                localTransaction = localTransaction(request, signedTransaction.prepared, result, timestamp / 1_000),
                spentOutputIds = selectedMwebOutputIds,
                createdUtxos = localCreatedUtxos(request, signedTransaction.prepared, result),
            )
            applyUtxoSnapshot(utxoSynchronizer.loadStoredSnapshot())
            result to signedTransaction.publicTransaction
        }

        processCreatedPublicTransaction(publicTransactionBridge, publicTransaction)
        result
    }

    suspend fun createSignedTransaction(
        request: MwebSendRequest,
        publicOptions: MwebPublicSendOptions,
        publicTransactionBridge: MwebPublicTransactionBridge? = null,
    ): MwebSignedRawTransaction = withContext(dispatcherProvider.io) {
        stateMutex.withLock {
            createSignedMwebTransaction(
                request = request,
                publicOptions = publicOptions,
                publicTransactionBridge = publicTransactionBridge,
            ).toSignedRawTransaction(request)
        }
    }

    suspend fun broadcastRawTransaction(rawTransaction: ByteArray): String = withContext(dispatcherProvider.io) {
        stateMutex.withLock {
            MwebDaemonErrorMapper.mapSuspend {
                requireStartedClient().broadcast(rawTransaction)
            }
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

    fun syncPublicTransactions(publicTransactionBridge: MwebPublicTransactionBridge) {
        runOnIoBlocking {
            syncPublicTransactionsSuspending(publicTransactionBridge)
        }
    }

    fun syncPublicTransactionsAsync(publicTransactionBridge: MwebPublicTransactionBridge) {
        coroutineScope.launch {
            try {
                syncPublicTransactionsSuspending(publicTransactionBridge)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.d(error) { "Failed to sync public MWEB transactions" }
            }
        }
    }

    /**
     * Returns user-visible MWEB history built from local sends and received UTXOs.
     *
     * This is a synchronous wrapper over blocking storage/state work; do not call from
     * Android main thread.
     */
    fun transactions(): List<MwebTransaction> {
        return runOnIoBlocking {
            transactionsCache?.takeIf { cached -> cached.none { it.pending } }?.let { return@runOnIoBlocking it }

            val knownUtxos = stateMutex.withLock { utxos }
            transactions(storage.localTransactions(), knownUtxos)
                .also { transactionsCache = it }
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
                    restoreCheckpoint = restoreCheckpointProvider(networkType, restoreHeight),
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
        utxoSynchronizer.flushPendingUtxosAndLoadSnapshot()?.let { snapshot ->
            applyUtxoSnapshot(snapshot)
        }
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

    private suspend fun createSignedMwebTransaction(
        request: MwebSendRequest,
        publicOptions: MwebPublicSendOptions,
        publicTransactionBridge: MwebPublicTransactionBridge?,
    ): CreatedMwebTransaction {
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
        val rawTransaction = prepared.rawTransactionWithPublicChange(createResult.rawTransaction)
        val signedPublicTransaction = signPublicInputs(
            rawTransaction = rawTransaction,
            selectedPublicUtxos = prepared.selectedPublicUtxos,
            publicTransactionBridge = publicTransactionBridge,
        )
        return CreatedMwebTransaction(
            prepared = prepared,
            createResult = createResult,
            signedPublicTransaction = signedPublicTransaction,
        )
    }

    private fun transactions(
        localTransactions: List<MwebTransaction>,
        knownUtxos: List<MwebUtxo>,
    ): List<MwebTransaction> {
        val pruneResult = pruneStaleLocalTransactions(localTransactions, knownUtxos)
        val visibleLocalTransactions = pruneResult.transactions
        val visibleUtxos = pruneResult.knownUtxos
        val locallyCreatedOutputIds = visibleLocalTransactions
            .flatMap { it.outputIds }
            .toSet()
        val incomingTransactions = visibleUtxos
            .filter { it.addressIndex > 0 && it.outputId !in locallyCreatedOutputIds }
            .map { it.incomingTransaction() }
        val transactions = incomingTransactions + visibleLocalTransactions.map { it.withStatusFrom(visibleUtxos) }
        return transactions.sortedWith(compareByDescending<MwebTransaction> { it.timestamp }.thenByDescending { it.uid })
    }

    private fun pruneStaleLocalTransactions(
        localTransactions: List<MwebTransaction>,
        knownUtxos: List<MwebUtxo>,
    ): LocalTransactionPruneResult {
        val now = currentTimeMillisProvider()
        val staleBeforeMillis = (now - localTransactionTtlMillis).coerceAtLeast(0)
        storage.deletePendingTransactionsOlderThan(staleBeforeMillis)

        val staleTransactions = localTransactions
            .filter { it.isStale(now / 1_000, knownUtxos) }
        val staleUids = staleTransactions.map { it.uid }
        val staleOutputIds = staleTransactions.flatMap { it.outputIds }.distinct()
        storage.deleteOutgoingTransactions(staleUids)
        storage.deleteUnconfirmedUtxos(staleOutputIds)
        val freshUtxos = if (staleOutputIds.isNotEmpty()) {
            val snapshot = utxoSynchronizer.loadStoredSnapshot()
            applyUtxoSnapshot(snapshot, notify = false)
            snapshot.utxos
        } else {
            knownUtxos
        }
        return LocalTransactionPruneResult(
            transactions = localTransactions.filter { it.uid !in staleUids },
            knownUtxos = freshUtxos,
        )
    }

    private data class LocalTransactionPruneResult(
        val transactions: List<MwebTransaction>,
        val knownUtxos: List<MwebUtxo>,
    )

    private suspend fun syncPublicTransactionsSuspending(publicTransactionBridge: MwebPublicTransactionBridge) {
        stateMutex.withLock {
            val recoveredUtxos = publicToMwebUtxosRecoveredFromPublicChain(
                localTransactions = storage.localTransactions(),
                knownUtxos = utxos,
                publicTransactionBridge = publicTransactionBridge,
            )
            if (recoveredUtxos.isEmpty()) return@withLock

            storage.saveUtxos(recoveredUtxos)
            applyUtxoSnapshot(utxoSynchronizer.loadStoredSnapshot())
        }
    }

    private fun publicToMwebUtxosRecoveredFromPublicChain(
        localTransactions: List<MwebTransaction>,
        knownUtxos: List<MwebUtxo>,
        publicTransactionBridge: MwebPublicTransactionBridge,
    ): List<MwebUtxo> {
        val knownUtxosByOutputId = knownUtxos.associateBy { it.outputId }
        val addressIndexes = storage.addresses().associate { it.address to it.index }

        return localTransactions.mapNotNull { transaction ->
            transaction.publicToMwebUtxoRecoveredFromPublicChain(
                knownUtxosByOutputId = knownUtxosByOutputId,
                addressIndexes = addressIndexes,
                publicTransactionBridge = publicTransactionBridge,
            )
        }
    }

    private fun MwebTransaction.publicToMwebUtxoRecoveredFromPublicChain(
        knownUtxosByOutputId: Map<String, MwebUtxo>,
        addressIndexes: Map<String, Int>,
        publicTransactionBridge: MwebPublicTransactionBridge,
    ): MwebUtxo? {
        if (kind != MwebTransactionKind.PublicToMweb || !pending) return null

        val outputId = outputIds.singleOrNull() ?: return null
        val knownUtxo = knownUtxosByOutputId[outputId]
        if (knownUtxo?.confirmed == true) return null

        val address = knownUtxo?.address?.takeIf { it.isNotBlank() } ?: address ?: return null
        val addressIndex = knownUtxo?.addressIndex?.takeIf { it > MwebAddressPool.CHANGE_INDEX }
            ?: addressIndexes[address]?.takeIf { it > MwebAddressPool.CHANGE_INDEX }
            ?: return null
        val hash = canonicalTransactionHash?.takeIf { it.isNotBlank() } ?: return null
        val status = publicTransactionBridge.transactionStatus(hash) ?: return null
        val height = status.height?.takeIf { it > 0 } ?: return null

        return MwebUtxo(
            outputId = outputId,
            address = address,
            addressIndex = addressIndex,
            value = knownUtxo?.value?.takeIf { it > 0 } ?: amount,
            height = height,
            blockTime = status.timestamp.takeIf { it > 0 } ?: timestamp,
            spent = false,
        )
    }

    private fun MwebUtxo.incomingTransaction(): MwebTransaction {
        return MwebTransaction(
            uid = "mweb-incoming:$outputId",
            type = MwebTransactionType.Incoming,
            kind = MwebTransactionKind.Incoming,
            amount = value,
            fee = null,
            address = address.takeIf { it.isNotBlank() },
            canonicalTransactionHash = null,
            outputIds = listOf(outputId),
            inputOutputIds = emptyList(),
            height = height.takeIf { it > 0 },
            timestamp = blockTime,
            pending = !confirmed,
        )
    }

    private fun MwebTransaction.withStatusFrom(knownUtxos: List<MwebUtxo>): MwebTransaction {
        val createdUtxo = knownUtxos.firstOrNull { it.outputId in outputIds && it.confirmed }
        return copy(
            height = createdUtxo?.height ?: height,
            timestamp = createdUtxo?.blockTime?.takeIf { it > 0 } ?: timestamp,
            pending = pending && createdUtxo == null && height == null,
        )
    }

    private fun MwebTransaction.isStale(now: Long, knownUtxos: List<MwebUtxo>): Boolean {
        if (!pending || height != null) return false
        if (knownUtxos.any { it.outputId in outputIds && it.confirmed }) return false

        return now - timestamp >= localTransactionTtlMillis / 1_000
    }

    private fun localTransaction(
        request: MwebSendRequest,
        prepared: PreparedMwebTransaction,
        result: MwebSendResult,
        timestamp: Long,
    ): MwebTransaction {
        val kind = when (request) {
            is MwebSendRequest.PublicToMweb -> MwebTransactionKind.PublicToMweb
            is MwebSendRequest.MwebToPublic -> MwebTransactionKind.MwebToPublic
            is MwebSendRequest.MwebToMweb -> MwebTransactionKind.MwebToMweb
        }
        val type = when (request) {
            is MwebSendRequest.PublicToMweb -> MwebTransactionType.Incoming
            is MwebSendRequest.MwebToPublic,
            is MwebSendRequest.MwebToMweb -> MwebTransactionType.Outgoing
        }
        return MwebTransaction(
            uid = MwebTransactionUid.local(type, result.canonicalTransactionHash ?: result.outputIds.firstOrNull() ?: timestamp.toString()),
            type = type,
            kind = kind,
            amount = request.value,
            fee = prepared.normalFee + prepared.mwebFee,
            address = request.address,
            canonicalTransactionHash = result.canonicalTransactionHash,
            outputIds = result.outputIds,
            inputOutputIds = prepared.selectedMwebUtxos.map { it.outputId },
            height = null,
            timestamp = timestamp,
            pending = when (request) {
                is MwebSendRequest.PublicToMweb -> result.outputIds.isNotEmpty()
                is MwebSendRequest.MwebToPublic,
                is MwebSendRequest.MwebToMweb -> prepared.selectedMwebUtxos.isNotEmpty()
            },
        )
    }

    private fun localCreatedUtxos(
        request: MwebSendRequest,
        prepared: PreparedMwebTransaction,
        result: MwebSendResult,
    ): List<MwebUtxo> {
        if (request !is MwebSendRequest.MwebToPublic) return emptyList()

        val outputId = result.outputIds.singleOrNull() ?: return emptyList()
        val changeValue = prepared.changeValue ?: return emptyList()
        val changeAddress = prepared.changeAddress ?: return emptyList()

        return listOf(
            MwebUtxo(
                outputId = outputId,
                address = changeAddress,
                addressIndex = MwebAddressPool.CHANGE_INDEX,
                value = changeValue,
                height = 0,
                blockTime = 0,
                spent = false,
            )
        )
    }

    private suspend fun signPublicInputs(
        rawTransaction: ByteArray,
        selectedPublicUtxos: List<UnspentOutput>,
        publicTransactionBridge: MwebPublicTransactionBridge?,
    ): MwebSignedPublicTransaction {
        if (selectedPublicUtxos.isEmpty()) {
            return MwebSignedPublicTransaction(rawTransaction, publicTransaction = null)
        }
        return requirePublicBridge(publicTransactionBridge).signPublicInputs(
            rawTransaction = rawTransaction,
            selectedPublicUtxos = selectedPublicUtxos,
        )
    }

    private fun requirePublicBridge(publicTransactionBridge: MwebPublicTransactionBridge?): MwebPublicTransactionBridge {
        return publicTransactionBridge ?: throw MwebError.NativeUnavailable()
    }

    private fun processCreatedPublicTransaction(
        publicTransactionBridge: MwebPublicTransactionBridge?,
        transaction: FullTransaction?,
    ) {
        if (transaction == null) return
        val bridge = publicTransactionBridge ?: run {
            log.d { "Skipping public MWEB transaction processing: bridge is missing" }
            return
        }

        try {
            bridge.processCreated(transaction)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The daemon broadcast and MWEB storage update already succeeded, so this
            // secondary public-side enqueue failure must not leave Send spinning.
            log.d(error) {
                "Failed to enqueue public MWEB transaction after daemon broadcast"
            }
        }
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
        transactionsCache = null
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
        private const val STATUS_POLL_INTERVAL_MILLIS = 5_000L
        private const val LOCAL_TRANSACTION_TTL_MILLIS = 24 * 60 * 60 * 1_000L

        fun clear(dataDir: String, mwebDataDir: String, networkType: LitecoinKit.NetworkType, walletId: String) {
            MwebFiles.clear(dataDir, mwebDataDir, networkType, walletId)
        }
    }
}

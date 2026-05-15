package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClient
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonStatus
import io.horizontalsystems.litecoinkit.mweb.storage.MwebRoomStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Direct engine entry points are serialized by the shared stateMutex; daemon callbacks
 * and background jobs acquire the same mutex before touching mutable state or storage.
 */
internal class MwebUtxoSynchronizer(
    private val storage: MwebRoomStorage,
    private val coroutineScope: CoroutineScope,
    private val stateMutex: Mutex,
    private val restoreHeight: Int,
    private val spentPollIntervalMillis: Long,
    private val statusPollIntervalMillis: Long,
    private val syncStateProvider: () -> MwebSyncState,
    private val activeClientProvider: () -> MwebDaemonClient?,
    private val isActiveClient: (MwebDaemonClient) -> Boolean,
    private val canonicalTransactionHashProvider: MwebCanonicalTransactionHashProvider,
    private val onNativeUnavailable: () -> Unit,
    private val onStatus: (MwebDaemonStatus) -> Unit,
    private val onSnapshot: (MwebUtxoSnapshot) -> Unit,
) {
    private var statusPollJob: Job? = null
    private var spentPollJob: Job? = null
    private var canonicalHashJob: Job? = null
    private var utxoFlushJob: Job? = null
    private var utxoStream: Closeable? = null
    private val pendingUtxos = mutableListOf<MwebUtxo>()

    fun stop() {
        statusPollJob?.cancel()
        statusPollJob = null
        spentPollJob?.cancel()
        spentPollJob = null
        canonicalHashJob?.cancel()
        canonicalHashJob = null
        utxoFlushJob?.cancel()
        utxoFlushJob = null
        flushPendingUtxosLocked()
        closeUtxoStream()
    }

    fun refresh(client: MwebDaemonClient) {
        refreshSpentOutputs(client)
        scheduleCanonicalTransactionHashRefresh()
        startUtxoStream(client)
    }

    fun startStatusPolling(client: MwebDaemonClient) {
        statusPollJob?.cancel()
        statusPollJob = coroutineScope.launch {
            while (isActive) {
                delay(statusPollIntervalMillis)
                stateMutex.withLock {
                    if (!isActiveClient(client)) return@withLock

                    try {
                        refreshStatus(client)
                    } catch (error: Exception) {
                        handlePollingError(error)
                    }
                }
            }
        }
    }

    fun startSpentPolling(client: MwebDaemonClient) {
        spentPollJob?.cancel()
        spentPollJob = coroutineScope.launch {
            while (isActive) {
                delay(spentPollIntervalMillis)
                stateMutex.withLock {
                    if (!isActiveClient(client)) return@withLock

                    try {
                        refreshSpentOutputs(client)
                    } catch (error: Exception) {
                        handlePollingError(error)
                    }
                }
            }
        }
    }

    fun startUtxoStream(client: MwebDaemonClient) {
        closeUtxoStream()
        utxoStream = MwebDaemonErrorMapper.map {
            client.utxos(
                fromHeight = utxoStreamStartHeight(),
                onUtxo = ::onUtxo,
                onComplete = { onUtxoStreamComplete(client) },
                onError = ::onUtxoStreamError,
            )
        }
    }

    fun refreshSpentOutputs(client: MwebDaemonClient) {
        val unspentOutputIds = storage.confirmedUnspentUtxos().map { it.outputId }
        val localTransactionOutputIds = storage.pendingLocalSpentOutputIds()
        val outputIds = (unspentOutputIds + localTransactionOutputIds).distinct()
        if (outputIds.isEmpty()) return

        val spentOutputIds = MwebDaemonErrorMapper.map { client.spent(outputIds) }
        if (spentOutputIds.isEmpty()) return

        val status = MwebDaemonErrorMapper.map {
            client.status(MwebDaemonClient.DEFAULT_STATUS_TIMEOUT_MILLIS)
        }
        onStatus(status)
        applySpentOutputs(spentOutputIds, status)
    }

    fun scheduleCanonicalTransactionHashRefresh() {
        if (canonicalHashJob?.isActive == true) return

        canonicalHashJob = coroutineScope.launch {
            val updates = storage.mwebToPublicCanonicalHashHeights().mapNotNull { height ->
                canonicalTransactionHashProvider.transactionHash(height)?.let { height to it }
            }
            if (updates.isEmpty()) return@launch

            stateMutex.withLock {
                val client = activeClient ?: return@withLock
                if (!isActiveClient(client)) return@withLock

                if (storage.updateMwebToPublicCanonicalHashes(updates)) {
                    onSnapshot(loadStoredSnapshot())
                }
            }
        }
    }

    private fun refreshStatus(client: MwebDaemonClient) {
        val status = MwebDaemonErrorMapper.map {
            client.status(MwebDaemonClient.DEFAULT_STATUS_TIMEOUT_MILLIS)
        }
        onStatus(status)
    }

    private fun applySpentOutputs(outputIds: List<String>, status: MwebDaemonStatus) {
        storage.markSpent(outputIds)
        storage.confirmTransactionsSpending(
            outputIds = outputIds,
            height = status.syncState.mwebUtxosHeight,
            timestamp = status.blockTime.takeIf { it > 0 },
        )
        scheduleCanonicalTransactionHashRefresh()
        onSnapshot(loadStoredSnapshot())
    }

    fun flushPendingUtxosAndLoadSnapshot(): MwebUtxoSnapshot? {
        if (!flushPendingUtxosLocked()) return null

        return loadStoredSnapshot()
    }

    fun loadStoredSnapshot(): MwebUtxoSnapshot {
        storage.reconcileCreatedUtxos()
        val utxos = storage.utxos()
        return MwebUtxoSnapshot(utxos, calculateBalance(utxos))
    }

    private fun onUtxo(utxo: MwebUtxo) {
        coroutineScope.launch {
            stateMutex.withLock {
                pendingUtxos.add(utxo)
                scheduleUtxoFlushLocked()
            }
        }
    }

    private fun scheduleUtxoFlushLocked() {
        if (utxoFlushJob?.isActive == true) return

        utxoFlushJob = coroutineScope.launch {
            delay(UTXO_SNAPSHOT_DEBOUNCE_MILLIS)
            stateMutex.withLock {
                utxoFlushJob = null
                if (flushPendingUtxosLocked()) {
                    onSnapshot(loadStoredSnapshot())
                }
            }
        }
    }

    private fun flushPendingUtxosLocked(): Boolean {
        if (pendingUtxos.isEmpty()) return false

        val utxos = pendingUtxos.toList()
        storage.saveUtxos(utxos)
        pendingUtxos.clear()
        return true
    }

    private fun onUtxoStreamError(error: Throwable) {
        if (error is UnsatisfiedLinkError || error is NoClassDefFoundError) {
            coroutineScope.launch {
                stateMutex.withLock {
                    onNativeUnavailable()
                    closeUtxoStream()
                }
            }
            return
        }
        coroutineScope.launch {
            delay(UTXO_RECONNECT_DELAY_MILLIS)
            stateMutex.withLock {
                val client = activeClient ?: return@withLock
                if (!isActiveClient(client)) return@withLock
                startUtxoStream(client)
            }
        }
    }

    private fun onUtxoStreamComplete(streamClient: MwebDaemonClient) {
        coroutineScope.launch {
            stateMutex.withLock {
                if (!isActiveClient(streamClient)) return@withLock

                utxoFlushJob?.cancel()
                utxoFlushJob = null
                flushPendingUtxosLocked()
                val status = MwebDaemonErrorMapper.mapSuspend {
                    streamClient.status(MwebDaemonClient.DEFAULT_STATUS_TIMEOUT_MILLIS)
                }
                onStatus(status.completedUtxoScan())
                onSnapshot(loadStoredSnapshot())
            }
        }
    }

    private fun MwebDaemonStatus.completedUtxoScan(): MwebDaemonStatus {
        val currentState = syncState
        val completedHeight = minOf(currentState.blockHeaderHeight, currentState.mwebHeaderHeight)
        return copy(
            syncState = currentState.copy(
                mwebUtxosHeight = maxOf(currentState.mwebUtxosHeight, completedHeight),
            ),
        )
    }

    private fun handlePollingError(error: Exception) {
        if (error is CancellationException) throw error
        if (error !is MwebError.NativeUnavailable) {
            logger.log(Level.WARNING, "MWEB polling failed", error)
            return
        }

        onNativeUnavailable()
        closeUtxoStream()
    }

    private fun closeUtxoStream() {
        utxoStream?.close()
        utxoStream = null
    }

    private fun utxoStreamStartHeight(): Int {
        val syncedHeight = syncStateProvider().mwebUtxosHeight
        if (syncedHeight <= 0) return restoreHeight

        return maxOf(restoreHeight, syncedHeight - UTXO_RESCAN_OVERLAP_BLOCKS)
    }

    private fun calculateBalance(utxos: List<MwebUtxo>): MwebBalance {
        return MwebBalance(
            confirmed = utxos.filter { it.confirmed && !it.spent }.sumOf { it.value },
            unconfirmed = utxos.filter { !it.confirmed && !it.spent }.sumOf { it.value },
        )
    }

    private val activeClient: MwebDaemonClient?
        get() = activeClientProvider()

    private companion object {
        // About one Litecoin day; daemon status is a global leafset height, not a wallet delivery cursor.
        const val UTXO_RESCAN_OVERLAP_BLOCKS = 576
        const val UTXO_SNAPSHOT_DEBOUNCE_MILLIS = 100L
        const val UTXO_RECONNECT_DELAY_MILLIS = 1_000L
        val logger: Logger = Logger.getLogger(MwebUtxoSynchronizer::class.java.name)
    }
}

internal data class MwebUtxoSnapshot(
    val utxos: List<MwebUtxo>,
    val balance: MwebBalance,
)

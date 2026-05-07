package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClient
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonStatus
import io.horizontalsystems.litecoinkit.mweb.storage.MwebRoomStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

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
    private val onNativeUnavailable: () -> Unit,
    private val onStatus: (MwebDaemonStatus) -> Unit,
    private val onSnapshot: (MwebUtxoSnapshot) -> Unit,
) {
    private var statusPollJob: Job? = null
    private var spentPollJob: Job? = null
    private var utxoStream: Closeable? = null

    fun stop() {
        statusPollJob?.cancel()
        statusPollJob = null
        spentPollJob?.cancel()
        spentPollJob = null
        closeUtxoStream()
    }

    fun refresh(client: MwebDaemonClient) {
        refreshSpentOutputs(client)
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
                    } catch (error: MwebError) {
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
                    } catch (error: MwebError) {
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
                fromHeight = syncStateProvider().mwebUtxosHeight.takeIf { it > 0 } ?: restoreHeight,
                onUtxo = ::onUtxo,
                onComplete = { onUtxoStreamComplete(client) },
                onError = ::onUtxoStreamError,
            )
        }
    }

    fun refreshSpentOutputs(client: MwebDaemonClient) {
        val unspentOutputIds = storage.unspentUtxos().map { it.outputId }
        if (unspentOutputIds.isEmpty()) return

        val spentOutputIds = MwebDaemonErrorMapper.map { client.spent(unspentOutputIds) }
        if (spentOutputIds.isEmpty()) return

        markSpent(spentOutputIds)
    }

    private fun refreshStatus(client: MwebDaemonClient) {
        val status = MwebDaemonErrorMapper.map {
            client.status(MwebDaemonClient.DEFAULT_STATUS_TIMEOUT_MILLIS)
        }
        onStatus(status)
    }

    fun markSpent(outputIds: List<String>) {
        storage.markSpent(outputIds)
        onSnapshot(loadSnapshot())
    }

    fun loadSnapshot(): MwebUtxoSnapshot {
        val utxos = storage.utxos()
        return MwebUtxoSnapshot(utxos, calculateBalance(utxos))
    }

    private fun onUtxo(utxo: MwebUtxo) {
        coroutineScope.launch {
            stateMutex.withLock {
                storage.saveUtxos(listOf(utxo))
                onSnapshot(loadSnapshot())
            }
        }
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

                val status = MwebDaemonErrorMapper.mapSuspend {
                    streamClient.status(MwebDaemonClient.DEFAULT_STATUS_TIMEOUT_MILLIS)
                }
                onStatus(status.completedUtxoScan())
                onSnapshot(loadSnapshot())
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

    private fun handlePollingError(error: MwebError) {
        if (error !is MwebError.NativeUnavailable) return

        onNativeUnavailable()
        closeUtxoStream()
    }

    private fun closeUtxoStream() {
        utxoStream?.close()
        utxoStream = null
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
        const val UTXO_RECONNECT_DELAY_MILLIS = 1_000L
    }
}

internal data class MwebUtxoSnapshot(
    val utxos: List<MwebUtxo>,
    val balance: MwebBalance,
)

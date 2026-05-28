package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressPool
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClient
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonStatus
import io.horizontalsystems.litecoinkit.mweb.storage.MwebRoomStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.Closeable

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
    private val replayCompleteTimeoutMillis: Long = DEFAULT_REPLAY_COMPLETE_TIMEOUT_MILLIS,
    private val streamHealthyThresholdMillis: Long = DEFAULT_STREAM_HEALTHY_THRESHOLD_MILLIS,
) {
    private var statusPollJob: Job? = null
    private var spentPollJob: Job? = null
    private var canonicalHashJob: Job? = null
    private var utxoFlushJob: Job? = null
    private var streamEventsJob: Job? = null
    private var streamReconnectJob: Job? = null
    private var streamHealthyJob: Job? = null
    private var replayCompleteTimeoutJob: Job? = null
    private var utxoStream: Closeable? = null
    private var streamEvents: Channel<StreamEvent>? = null
    private var streamGeneration = 0L
    private var activeStreamGeneration = NO_ACTIVE_STREAM
    private var terminallyEndedGeneration = NO_ACTIVE_STREAM
    private var consecutiveStreamFailures = 0
    private var nativeReplayCompleteSupported: Boolean? = null
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
        val generation = ++streamGeneration
        activeStreamGeneration = generation

        val channel = Channel<StreamEvent>(Channel.UNLIMITED)
        streamEvents = channel
        streamEventsJob = coroutineScope.launch {
            for (event in channel) {
                stateMutex.withLock { handleStreamEvent(event) }
            }
        }

        val fromHeight = utxoStreamStartHeight()
        Timber.tag(LOG_TAG).d("MWEB utxo stream subscribe fromHeight=$fromHeight gen=$generation")
        utxoStream = MwebDaemonErrorMapper.map {
            client.utxos(
                fromHeight = fromHeight,
                onUtxo = { utxo -> dispatchEvent(channel, StreamEvent.Utxo(utxo, generation), terminal = false) },
                onReplayComplete = { height ->
                    dispatchEvent(channel, StreamEvent.ReplayComplete(height, generation), terminal = false)
                },
                onComplete = {
                    dispatchEvent(channel, StreamEvent.Ended(client, generation, StreamEndReason.COMPLETE), terminal = true)
                },
                onError = { error ->
                    dispatchEvent(channel, StreamEvent.Ended(client, generation, StreamEndReason.ERROR, error), terminal = true)
                },
            )
        }
        if (nativeReplayCompleteSupported == null) {
            scheduleReplayCompleteTimeout(generation)
        }
        scheduleStreamHealthyReset(generation)
    }

    fun refreshSpentOutputs(client: MwebDaemonClient) {
        val pendingLocalSpentOutputIds = storage.pendingLocalSpentOutputIds().toSet()
        val candidates = storage.utxos().filter { it.shouldCheckSpent(pendingLocalSpentOutputIds) }
        if (candidates.isEmpty()) return

        val status = MwebDaemonErrorMapper.map {
            client.status(MwebDaemonClient.DEFAULT_STATUS_TIMEOUT_MILLIS)
        }
        onStatus(status)

        val outputIds = candidates.outputIdsCoveredBy(status.syncState.mwebUtxosHeight)
        if (outputIds.isEmpty()) return

        val spentOutputIds = MwebDaemonErrorMapper.map { client.spent(outputIds) }
        if (spentOutputIds.isEmpty()) return

        applySpentOutputs(spentOutputIds, status)
    }

    private fun MwebUtxo.shouldCheckSpent(pendingLocalSpentOutputIds: Set<String>): Boolean {
        if (!confirmed) return false
        if (outputId in pendingLocalSpentOutputIds) return true

        return !spent && addressIndex == MwebAddressPool.CHANGE_INDEX
    }

    private fun List<MwebUtxo>.outputIdsCoveredBy(mwebUtxosHeight: Int): List<String> {
        if (mwebUtxosHeight <= 0) return emptyList()

        return filter { it.height <= mwebUtxosHeight }
            .map { it.outputId }
            .distinct()
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

    fun flushPendingUtxosAndLoadSnapshot(): MwebUtxoSnapshot? {
        if (!flushPendingUtxosLocked()) return null

        return loadStoredSnapshot()
    }

    fun loadStoredSnapshot(): MwebUtxoSnapshot {
        storage.reconcileCreatedUtxos()
        val utxos = storage.utxos()
        return MwebUtxoSnapshot(utxos, calculateBalance(utxos))
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

    private fun dispatchEvent(channel: Channel<StreamEvent>, event: StreamEvent, terminal: Boolean) {
        val result = channel.trySend(event)
        if (result.isSuccess) return

        if (!terminal) {
            Timber.tag(LOG_TAG).d("MWEB utxo stream event dropped after close: ${event.safeDescription()}")
            return
        }

        Timber.tag(LOG_TAG).w("MWEB terminal stream event dropped after close: ${event.safeDescription()}")
        coroutineScope.launch {
            stateMutex.withLock {
                if (event.generation != activeStreamGeneration) return@withLock
                if (terminallyEndedGeneration >= event.generation) return@withLock
                handleStreamEvent(event)
            }
        }
    }

    private fun handleStreamEvent(event: StreamEvent) {
        when (event) {
            is StreamEvent.Utxo -> handleStreamUtxo(event.utxo)
            is StreamEvent.ReplayComplete -> handleReplayComplete(event)
            is StreamEvent.Ended -> handleStreamEnded(event)
        }
    }

    private fun handleStreamUtxo(utxo: MwebUtxo) {
        Timber.tag(LOG_TAG).v(
            "MWEB utxo queued outputId=${utxo.outputId.take(LOG_OUTPUT_ID_PREFIX_LENGTH)} height=${utxo.height}"
        )
        pendingUtxos.add(utxo)
        scheduleUtxoFlushLocked()
    }

    private fun handleReplayComplete(event: StreamEvent.ReplayComplete) {
        if (event.generation != activeStreamGeneration) return

        nativeReplayCompleteSupported = true
        replayCompleteTimeoutJob?.cancel()
        replayCompleteTimeoutJob = null
        utxoFlushJob?.cancel()
        utxoFlushJob = null
        val changed = flushPendingUtxosLocked()
        storage.advanceUtxoDeliveryHeight(event.height)
        Timber.tag(LOG_TAG).d("MWEB utxo replay complete height=${event.height} gen=${event.generation}")
        if (changed) {
            onSnapshot(loadStoredSnapshot())
        }
    }

    private fun handleStreamEnded(event: StreamEvent.Ended) {
        val generation = event.generation
        if (generation != activeStreamGeneration) {
            Timber.tag(LOG_TAG).d("MWEB utxo stream ended for stale gen=$generation reason=${event.reason}")
            return
        }
        if (terminallyEndedGeneration >= generation) {
            Timber.tag(LOG_TAG).d("MWEB utxo stream terminal already handled gen=$generation reason=${event.reason}")
            return
        }
        terminallyEndedGeneration = generation

        if (!isActiveClient(event.client)) return

        val error = event.error
        if (error is UnsatisfiedLinkError || error is NoClassDefFoundError) {
            onNativeUnavailable()
            closeUtxoStream()
            return
        }

        streamHealthyJob?.cancel()
        streamHealthyJob = null
        utxoFlushJob?.cancel()
        utxoFlushJob = null
        if (flushPendingUtxosLocked()) {
            onSnapshot(loadStoredSnapshot())
        }

        consecutiveStreamFailures += 1
        val delayMillis = currentBackoffMillis()
        if (error == null) {
            Timber.tag(LOG_TAG).w(
                "MWEB utxo stream ended reason=${event.reason} gen=$generation failures=$consecutiveStreamFailures reconnectIn=${delayMillis}ms"
            )
        } else {
            Timber.tag(LOG_TAG).w(
                error,
                "MWEB utxo stream ended reason=${event.reason} gen=$generation failures=$consecutiveStreamFailures reconnectIn=${delayMillis}ms"
            )
        }
        scheduleReconnect(event.client, generation, delayMillis)
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
        val heightRange = utxos.minOf { it.height }..utxos.maxOf { it.height }
        Timber.tag(LOG_TAG).d("MWEB utxo flush count=${utxos.size} heights=$heightRange")
        storage.saveUtxos(utxos)
        pendingUtxos.clear()
        storage.advanceUtxoDeliveryHeight(utxos.maxOf { it.height })
        return true
    }

    private fun scheduleReconnect(client: MwebDaemonClient, terminalGeneration: Long, delayMillis: Long) {
        streamReconnectJob?.cancel()
        streamReconnectJob = coroutineScope.launch {
            delay(delayMillis)
            stateMutex.withLock {
                if (activeStreamGeneration != terminalGeneration) return@withLock
                val current = activeClient ?: return@withLock
                if (!isActiveClient(current) || current !== client) return@withLock

                startUtxoStream(current)
            }
        }
    }

    private fun scheduleStreamHealthyReset(generation: Long) {
        streamHealthyJob?.cancel()
        streamHealthyJob = coroutineScope.launch {
            delay(streamHealthyThresholdMillis)
            stateMutex.withLock {
                if (generation != activeStreamGeneration) return@withLock
                if (terminallyEndedGeneration >= generation) return@withLock

                consecutiveStreamFailures = 0
                Timber.tag(LOG_TAG).d("MWEB utxo stream healthy gen=$generation")
            }
        }
    }

    private fun scheduleReplayCompleteTimeout(generation: Long) {
        replayCompleteTimeoutJob?.cancel()
        replayCompleteTimeoutJob = coroutineScope.launch {
            delay(replayCompleteTimeoutMillis)
            stateMutex.withLock {
                if (generation != activeStreamGeneration) return@withLock
                if (nativeReplayCompleteSupported != null) return@withLock

                nativeReplayCompleteSupported = false
                Timber.tag(LOG_TAG).w("MWEB native replay-complete marker missing; using legacy UTXO cursor")
            }
        }
    }

    private fun currentBackoffMillis(): Long {
        return when (consecutiveStreamFailures) {
            0, 1 -> UTXO_RECONNECT_DELAY_MILLIS
            2 -> 2_000L
            3 -> 5_000L
            else -> MAX_RECONNECT_DELAY_MILLIS
        }
    }

    private fun handlePollingError(error: Exception) {
        if (error is CancellationException) throw error
        if (error !is MwebError.NativeUnavailable) {
            Timber.tag(LOG_TAG).w(error, "MWEB polling failed")
            return
        }

        onNativeUnavailable()
        closeUtxoStream()
    }

    private fun closeUtxoStream() {
        utxoStream?.close()
        utxoStream = null
        streamEvents?.close()
        streamEvents = null
        streamEventsJob?.cancel()
        streamEventsJob = null
        streamReconnectJob?.cancel()
        streamReconnectJob = null
        streamHealthyJob?.cancel()
        streamHealthyJob = null
        replayCompleteTimeoutJob?.cancel()
        replayCompleteTimeoutJob = null
        activeStreamGeneration = NO_ACTIVE_STREAM
    }

    private fun utxoStreamStartHeight(): Int {
        val cursor = when (nativeReplayCompleteSupported) {
            false -> syncStateProvider().mwebUtxosHeight
            true, null -> storage.deliveryState().utxoDeliveryHeight
        }
        if (cursor <= 0) return restoreHeight

        return maxOf(restoreHeight, cursor - UTXO_RESCAN_OVERLAP_BLOCKS)
    }

    private fun calculateBalance(utxos: List<MwebUtxo>): MwebBalance {
        return MwebBalance(
            confirmed = utxos.filter { it.confirmed && !it.spent }.sumOf { it.value },
            unconfirmed = utxos.filter { !it.confirmed && !it.spent }.sumOf { it.value },
        )
    }

    private val activeClient: MwebDaemonClient?
        get() = activeClientProvider()

    private sealed class StreamEvent {
        abstract val generation: Long

        fun safeDescription(): String {
            return when (this) {
                is Utxo -> "utxo(gen=$generation, outputId=${utxo.outputId.take(LOG_OUTPUT_ID_PREFIX_LENGTH)}, height=${utxo.height})"
                is ReplayComplete -> "replayComplete(gen=$generation, height=$height)"
                is Ended -> "ended(gen=$generation, reason=$reason)"
            }
        }

        data class Utxo(val utxo: MwebUtxo, override val generation: Long) : StreamEvent()
        data class ReplayComplete(val height: Int, override val generation: Long) : StreamEvent()
        data class Ended(
            val client: MwebDaemonClient,
            override val generation: Long,
            val reason: StreamEndReason,
            val error: Throwable? = null,
        ) : StreamEvent()
    }

    private enum class StreamEndReason {
        COMPLETE,
        ERROR,
    }

    internal companion object {
        const val UTXO_RESCAN_OVERLAP_BLOCKS = 2_880
        const val UTXO_SNAPSHOT_DEBOUNCE_MILLIS = 100L
        const val UTXO_RECONNECT_DELAY_MILLIS = 1_000L
        const val MAX_RECONNECT_DELAY_MILLIS = 15_000L
        const val DEFAULT_STREAM_HEALTHY_THRESHOLD_MILLIS = 30_000L
        const val DEFAULT_REPLAY_COMPLETE_TIMEOUT_MILLIS = 5 * 60_000L
        private const val LOG_OUTPUT_ID_PREFIX_LENGTH = 8
        private const val LOG_TAG = "MwebUtxoSync"
        private const val NO_ACTIVE_STREAM = -1L
    }
}

internal data class MwebUtxoSnapshot(
    val utxos: List<MwebUtxo>,
    val balance: MwebBalance,
)

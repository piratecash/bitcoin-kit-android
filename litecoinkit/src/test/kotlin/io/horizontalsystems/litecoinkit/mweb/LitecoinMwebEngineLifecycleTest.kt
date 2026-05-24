package io.horizontalsystems.litecoinkit.mweb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.LitecoinTransactionSerializer
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressCodec
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClient
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonConfig
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonStatus
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebCreateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LitecoinMwebEngineLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val walletIds = mutableListOf<String>()
    private val engines = mutableListOf<LitecoinMwebEngine>()
    private val publicPegInSenders = mutableListOf<MwebPublicPegInSender>()
    private val ioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val dispatcherProvider = CoroutineMwebDispatcherProvider(io = ioDispatcher, callback = ImmediateDispatcher)
    private val transactionSerializer = LitecoinTransactionSerializer()

    @After
    fun tearDown() {
        engines.forEach { engine -> engine.dispose() }
        publicPegInSenders.forEach { sender -> sender.stop() }
        walletIds.forEach { walletId ->
            LitecoinMwebEngine.clear(context, LitecoinKit.NetworkType.MainNet, walletId)
        }
        ioDispatcher.close()
    }

    @Test
    fun start_statusTimeout_throwsNativeUnavailable() {
        val engine = engineWith(FakeDaemonClient(startError = TimeoutException()))

        assertThrows(MwebError.NativeUnavailable::class.java) {
            engine.start()
        }
    }

    @Test
    fun start_daemonException_throwsDaemonCrashed() {
        val engine = engineWith(FakeDaemonClient(startError = IllegalStateException("boom")))

        assertThrows(MwebError.DaemonCrashed::class.java) {
            engine.start()
        }
    }

    @Test
    fun start_success_updatesDebugInfo() {
        val status = MwebDaemonStatus(
            syncState = MwebSyncState(
                blockHeaderHeight = 10,
                mwebHeaderHeight = 9,
                mwebUtxosHeight = 8,
            ),
            nativeVersion = "test-native",
        )
        val engine = engineWith(FakeDaemonClient(status = status))

        engine.start()

        assertEquals(status.syncState, engine.syncState)
        assertEquals(status.nativeVersion, engine.debugInfo().nativeVersion)
    }

    @Test
    fun start_restorePointWithCheckpoint_passesCheckpointToDaemonConfig() {
        var capturedConfig: MwebDaemonConfig? = null
        val engine = engineWith(
            daemonClient = FakeDaemonClient(),
            restorePoint = MwebRestorePoint.BlockHeight(2_900_000),
            restoreCheckpointProvider = { networkType, restoreHeight ->
                assertEquals(LitecoinKit.NetworkType.MainNet, networkType)
                assertEquals(2_900_000, restoreHeight)
                "mweb-checkpoint-v1|2900000|checkpoint"
            },
            daemonClientFactory = { config ->
                capturedConfig = config
                FakeDaemonClient()
            },
        )

        engine.start()

        assertEquals(2_900_000, capturedConfig?.restoreHeight)
        assertEquals("mweb-checkpoint-v1|2900000|checkpoint", capturedConfig?.restoreCheckpoint)
    }

    @Test
    fun refresh_beforeStart_doesNothing() {
        val engine = engineWith(FakeDaemonClient())

        engine.refresh()

        assertEquals(MwebSyncState(0, 0, 0), engine.syncState)
    }

    @Test
    fun start_utxoStream_updatesBalanceAndNotifiesListener() {
        val daemonClient = FakeDaemonClient(
            streamUtxos = listOf(
                MwebUtxo("confirmed", "address", 1, 100, 10, 1_000, spent = false),
                MwebUtxo("unconfirmed", "address", 1, 50, 0, 0, spent = false),
            )
        )
        val engine = engineWith(daemonClient)
        val balanceUpdates = mutableListOf<MwebBalance>()
        engine.addListener(object : LitecoinMwebEngine.Listener {
            override fun onMwebBalanceUpdate(balance: MwebBalance) {
                balanceUpdates.add(balance)
            }
        })

        engine.start()
        waitUntil { engine.balance == MwebBalance(confirmed = 100, unconfirmed = 50) }

        assertEquals(MwebBalance(confirmed = 100, unconfirmed = 50), engine.balance)
        assertEquals(MwebBalance(confirmed = 100, unconfirmed = 50), balanceUpdates.last())
        assertTrue(daemonClient.utxoFromHeights.isNotEmpty())
    }

    @Test
    fun start_replayedUtxos_batchesSnapshotNotification() {
        val daemonClient = FakeDaemonClient(
            streamUtxos = listOf(
                MwebUtxo("confirmed", "address", 1, 100, 10, 1_000, spent = false),
                MwebUtxo("unconfirmed", "address", 1, 50, 0, 0, spent = false),
            ),
            completeUtxoStream = true,
        )
        val engine = engineWith(daemonClient)
        val balanceUpdates = mutableListOf<MwebBalance>()
        val utxoUpdates = mutableListOf<List<MwebUtxo>>()
        engine.addListener(object : LitecoinMwebEngine.Listener {
            override fun onMwebBalanceUpdate(balance: MwebBalance) {
                balanceUpdates.add(balance)
            }

            override fun onMwebUtxosUpdate(utxos: List<MwebUtxo>) {
                utxoUpdates.add(utxos)
            }
        })

        engine.start()
        waitUntil { balanceUpdates.isNotEmpty() }

        assertEquals(listOf(MwebBalance(confirmed = 100, unconfirmed = 50)), balanceUpdates)
        assertEquals(listOf(setOf("confirmed", "unconfirmed")), utxoUpdates.map { update ->
            update.map { utxo -> utxo.outputId }.toSet()
        })
    }

    @Test
    fun start_daemonUtxoHeightWithoutDeliveryCursor_startsUtxoStreamFromRestoreHeight() {
        val daemonClient = FakeDaemonClient(
            status = MwebDaemonStatus(
                syncState = MwebSyncState(
                    blockHeaderHeight = 3_107_750,
                    mwebHeaderHeight = 3_107_750,
                    mwebUtxosHeight = 3_107_750,
                ),
                nativeVersion = "test-native",
            )
        )
        val engine = engineWith(daemonClient, restorePoint = MwebRestorePoint.BlockHeight(3_056_500))

        engine.start()

        assertEquals(3_056_500, daemonClient.utxoFromHeights.single())
    }

    @Test
    fun replayComplete_thenRefresh_startsUtxoStreamFromDeliveryCursorWithOverlap() {
        val daemonClient = FakeDaemonClient(
            status = MwebDaemonStatus(
                syncState = MwebSyncState(
                    blockHeaderHeight = 3_107_750,
                    mwebHeaderHeight = 3_107_750,
                    mwebUtxosHeight = 3_107_750,
                ),
                nativeVersion = "test-native",
            ),
            replayCompleteHeight = 3_107_750,
        )
        val engine = engineWith(daemonClient, restorePoint = MwebRestorePoint.BlockHeight(3_056_500))

        engine.start()
        waitUntil { daemonClient.utxoFromHeights.size == 1 }
        engine.refresh()
        waitUntil { daemonClient.utxoFromHeights.size == 2 }

        assertEquals(listOf(3_056_500, 3_104_870), daemonClient.utxoFromHeights)
    }

    @Test
    fun replayComplete_emptyUtxoStream_updatesDeliveryCursorWithoutSyncNotification() {
        val expectedSyncState = MwebSyncState(
            blockHeaderHeight = 2_300_000,
            mwebHeaderHeight = 2_300_000,
            mwebUtxosHeight = 0,
        )
        val daemonClient = FakeDaemonClient(
            status = MwebDaemonStatus(expectedSyncState, nativeVersion = "test-native"),
            replayCompleteHeight = 2_300_000,
        )
        val engine = engineWith(daemonClient, restorePoint = MwebRestorePoint.Activation)
        val syncUpdates = mutableListOf<MwebSyncState>()
        engine.addListener(object : LitecoinMwebEngine.Listener {
            override fun onMwebSyncStateUpdate(state: MwebSyncState) {
                syncUpdates.add(state)
            }
        })

        engine.start()
        waitUntil { daemonClient.utxoFromHeights.size == 1 }
        engine.refresh()
        waitUntil { daemonClient.utxoFromHeights.size == 2 }

        assertEquals(expectedSyncState, engine.syncState)
        assertEquals(listOf(2_257_920, 2_297_120), daemonClient.utxoFromHeights)
        assertTrue(syncUpdates.all { it == expectedSyncState })
    }

    @Test
    fun utxoStreamComplete_reopensStreamFromDeliveryCursorWithOverlap() {
        val daemonClient = FakeDaemonClient(
            status = MwebDaemonStatus(MwebSyncState(2_300_000, 2_300_000, 2_300_000), nativeVersion = "test-native"),
            replayCompleteHeight = 2_300_000,
            completeUtxoStream = true,
        )
        val engine = engineWith(daemonClient, restorePoint = MwebRestorePoint.Activation)

        engine.start()
        waitUntil(timeoutMillis = 2_500) { daemonClient.utxoFromHeights.size == 2 }

        assertEquals(listOf(2_257_920, 2_297_120), daemonClient.utxoFromHeights)
    }

    @Test
    fun statusPoll_emptyOpenUtxoStream_updatesDaemonUtxoHeightButNotDeliveryHeight() {
        val daemonClient = FakeDaemonClient(
            status = MwebDaemonStatus(MwebSyncState(100, 100, 0), nativeVersion = "test-native"),
        )
        val engine = engineWith(daemonClient, statusPollIntervalMillis = 10)
        val expectedSyncState = MwebSyncState(
            blockHeaderHeight = 100,
            mwebHeaderHeight = 100,
            mwebUtxosHeight = 100,
        )

        engine.start()
        daemonClient.status = MwebDaemonStatus(expectedSyncState, nativeVersion = "test-native")
        waitUntil { engine.syncState == expectedSyncState }

        assertEquals(expectedSyncState, engine.syncState)
        assertTrue(engine.mwebUtxos().isEmpty())
    }

    @Test
    fun utxoStream_errorThenCompleteSameGeneration_schedulesSingleReconnect() {
        val daemonClient = FakeDaemonClient()
        val engine = engineWith(daemonClient)

        engine.start()
        daemonClient.emitUtxoErrorAndComplete(IllegalStateException("stream failed"))
        waitUntil(timeoutMillis = 2_500) { daemonClient.utxoFromHeights.size == 2 }

        assertEquals(2, daemonClient.utxoFromHeights.size)
    }

    @Test
    fun utxoStream_lateUtxoAfterTerminal_isSavedBeforeReconnect() {
        val daemonClient = FakeDaemonClient()
        val engine = engineWith(daemonClient)

        engine.start()
        daemonClient.emitUtxoError(IllegalStateException("stream failed"))
        daemonClient.emitUtxo(MwebUtxo("late-output", "address", 1, 100, 10, 1_000, spent = false))
        waitUntil { engine.mwebUtxos().any { it.outputId == "late-output" } }

        assertTrue(engine.mwebUtxos().any { it.outputId == "late-output" })
    }

    @Test
    fun utxoStream_repeatedFailures_usesProgressiveBackoff() {
        val daemonClient = FakeDaemonClient(completeEveryUtxoStream = true)
        val engine = engineWith(daemonClient, restorePoint = MwebRestorePoint.BlockHeight(1))

        engine.start()
        waitUntil(timeoutMillis = 1_500) { daemonClient.utxoFromHeights.size == 2 }
        assertEquals(2, daemonClient.utxoFromHeights.size)
        Thread.sleep(1_200)
        assertEquals(2, daemonClient.utxoFromHeights.size)
        waitUntil(timeoutMillis = 1_500) { daemonClient.utxoFromHeights.size == 3 }

        assertEquals(3, daemonClient.utxoFromHeights.size)
    }

    @Test
    fun replayCompleteTimeout_legacyNative_usesDaemonUtxoHeightCursor() {
        val daemonClient = FakeDaemonClient(
            status = MwebDaemonStatus(MwebSyncState(3_107_750, 3_107_750, 3_107_750), nativeVersion = "test-native"),
        )
        val engine = engineWith(
            daemonClient = daemonClient,
            restorePoint = MwebRestorePoint.BlockHeight(3_056_500),
            replayCompleteTimeoutMillis = 50,
        )

        engine.start()
        Thread.sleep(100)
        engine.refresh()
        waitUntil { daemonClient.utxoFromHeights.size == 2 }

        assertEquals(listOf(3_056_500, 3_104_870), daemonClient.utxoFromHeights)
    }

    @Test
    fun refresh_spentOutputs_marksSpentAndUpdatesBalance() {
        val spentUtxo = changeUtxo(outputId = "spent-output")
        val daemonClient = FakeDaemonClient(
            status = syncedStatus(spentUtxo.height),
            streamUtxos = listOf(spentUtxo),
            spentOutputIds = listOf(spentUtxo.outputId),
        )
        val engine = engineWith(daemonClient)

        engine.start()
        waitUntil { engine.mwebUtxos().isNotEmpty() }
        engine.refresh()
        waitUntil { engine.balance == MwebBalance(confirmed = 0, unconfirmed = 0) }

        assertEquals(MwebBalance(confirmed = 0, unconfirmed = 0), engine.balance)
        assertTrue(engine.mwebUtxos().first().spent)
    }

    @Test
    fun refresh_receiveUtxoReportedSpent_keepsReceiveUtxoUnspent() {
        val receiveUtxo = receiveUtxo()
        val daemonClient = FakeDaemonClient(
            status = syncedStatus(receiveUtxo.height),
            streamUtxos = listOf(receiveUtxo),
        )
        val engine = engineWith(daemonClient)

        engine.start()
        waitUntil { engine.balance == MwebBalance(confirmed = receiveUtxo.value, unconfirmed = 0) }

        daemonClient.spentOutputIds = listOf(receiveUtxo.outputId)
        engine.refresh()

        val transaction = engine.transactions().single()
        assertFalse(engine.mwebUtxos().single().spent)
        assertEquals(MwebBalance(confirmed = receiveUtxo.value, unconfirmed = 0), engine.balance)
        assertEquals(MwebTransactionType.Incoming, transaction.type)
        assertFalse(transaction.pending)
    }

    @Test
    fun start_storedReceiveUtxoReportedSpent_keepsReceiveUtxoUnspent() {
        val receiveUtxo = receiveUtxo()
        val daemonClient = FakeDaemonClient(
            status = syncedStatus(receiveUtxo.height),
            streamUtxos = listOf(receiveUtxo),
        )
        val engine = engineWith(daemonClient)

        engine.start()
        waitUntil { engine.balance == MwebBalance(confirmed = receiveUtxo.value, unconfirmed = 0) }
        engine.stop()

        daemonClient.spentOutputIds = listOf(receiveUtxo.outputId)
        engine.start()

        assertFalse(engine.mwebUtxos().single().spent)
        assertEquals(MwebBalance(confirmed = receiveUtxo.value, unconfirmed = 0), engine.balance)
    }

    @Test
    fun spentPoll_receiveUtxoReportedSpent_keepsReceiveUtxoUnspent() {
        val receiveUtxo = receiveUtxo()
        val pollMarkerUtxo = changeUtxo(outputId = "poll-marker-output")
        val daemonClient = FakeDaemonClient(
            status = syncedStatus(receiveUtxo.height),
            streamUtxos = listOf(receiveUtxo, pollMarkerUtxo),
        )
        val engine = engineWith(daemonClient, spentPollIntervalMillis = 10)

        engine.start()
        waitUntil {
            engine.balance == MwebBalance(
                confirmed = receiveUtxo.value + pollMarkerUtxo.value,
                unconfirmed = 0,
            )
        }

        val spentCallCountBefore = daemonClient.spentRequests.size
        daemonClient.spentOutputIds = listOf(receiveUtxo.outputId)

        // Exit either when the bug fires (UTXO marked spent) or after two poll cycles
        // have checked the marker output, whichever happens first.
        waitUntil {
            engine.mwebUtxos().first { it.outputId == receiveUtxo.outputId }.spent ||
                daemonClient.spentRequests.size >= spentCallCountBefore + 2
        }

        assertFalse(engine.mwebUtxos().first { it.outputId == receiveUtxo.outputId }.spent)
        assertEquals(
            MwebBalance(confirmed = receiveUtxo.value + pollMarkerUtxo.value, unconfirmed = 0),
            engine.balance,
        )
    }

    @Test
    fun refresh_mixedSpentOutputsAtAndAboveDaemonUtxoHeight_marksOnlyOutputsAtOrBelowHeight() {
        val trustedUtxo = changeUtxo(outputId = "trusted-output", height = 10)
        val aheadUtxo = changeUtxo(outputId = "ahead-output", value = 200, height = 20)
        val daemonClient = FakeDaemonClient(
            status = syncedStatus(aheadUtxo.height),
            streamUtxos = listOf(trustedUtxo, aheadUtxo),
        )
        val engine = engineWith(daemonClient)

        engine.start()
        waitUntil {
            engine.balance == MwebBalance(
                confirmed = trustedUtxo.value + aheadUtxo.value,
                unconfirmed = 0,
            )
        }

        // Daemon CoinDB is behind local Room while headers still cover both UTXOs.
        daemonClient.status = statusWithUtxoHeight(
            chainHeight = aheadUtxo.height,
            mwebUtxosHeight = trustedUtxo.height,
        )
        daemonClient.spentOutputIds = listOf(trustedUtxo.outputId, aheadUtxo.outputId)
        engine.refresh()

        waitUntil {
            engine.mwebUtxos().firstOrNull { it.outputId == trustedUtxo.outputId }?.spent == true
        }

        val utxosById = engine.mwebUtxos().associateBy { it.outputId }
        assertTrue(utxosById.getValue(trustedUtxo.outputId).spent)
        assertFalse(utxosById.getValue(aheadUtxo.outputId).spent)
        assertEquals(MwebBalance(confirmed = aheadUtxo.value, unconfirmed = 0), engine.balance)
    }

    @Test
    fun refresh_mixedConfirmedAndUnconfirmedOutputs_checksOnlyConfirmedOutputs() {
        val confirmedUtxo = changeUtxo(outputId = "confirmed-output")
        val daemonClient = FakeDaemonClient(
            status = syncedStatus(confirmedUtxo.height),
            streamUtxos = listOf(
                confirmedUtxo,
                MwebUtxo("unconfirmed-change", "", 0, 2_161_921, 0, 0, spent = false),
            ),
            spentOutputIds = listOf("confirmed-output", "unconfirmed-change"),
        )
        val engine = engineWith(daemonClient)

        engine.start()
        waitUntil { engine.balance == MwebBalance(confirmed = 100, unconfirmed = 2_161_921) }
        engine.refresh()
        waitUntil { engine.balance == MwebBalance(confirmed = 0, unconfirmed = 2_161_921) }

        assertEquals(MwebBalance(confirmed = 0, unconfirmed = 2_161_921), engine.balance)
        assertEquals(listOf(listOf("confirmed-output")), daemonClient.spentRequests)
        assertTrue(engine.mwebUtxos().first { it.outputId == "confirmed-output" }.spent)
        assertFalse(engine.mwebUtxos().first { it.outputId == "unconfirmed-change" }.spent)
    }

    @Test
    fun spentPoll_started_marksSpentOutputsPeriodically() {
        val spentUtxo = changeUtxo(outputId = "spent-output")
        val daemonClient = FakeDaemonClient(
            status = syncedStatus(spentUtxo.height),
            streamUtxos = listOf(spentUtxo),
        )
        val engine = engineWith(daemonClient, spentPollIntervalMillis = 10)

        engine.start()
        waitUntil { engine.mwebUtxos().isNotEmpty() }
        daemonClient.spentOutputIds = listOf(spentUtxo.outputId)
        waitUntil { engine.balance == MwebBalance(confirmed = 0, unconfirmed = 0) }

        assertEquals(MwebBalance(confirmed = 0, unconfirmed = 0), engine.balance)
        assertTrue(engine.mwebUtxos().first().spent)
    }

    @Test
    fun stop_started_closesUtxoStreamAndStopsDaemon() {
        val daemonClient = FakeDaemonClient(
            streamUtxos = listOf(MwebUtxo("output", "address", 1, 100, 10, 1_000, spent = false)),
        )
        val engine = engineWith(daemonClient)

        engine.start()
        waitUntil { daemonClient.utxoFromHeights.isNotEmpty() }
        engine.stop()

        assertEquals(1, daemonClient.closedUtxoStreams)
        assertEquals(1, daemonClient.stopCount)
    }

    @Test
    fun utxoStream_nativeError_stopsMwebEngine() {
        val daemonClient = FakeDaemonClient()
        val engine = engineWith(daemonClient)

        engine.start()
        daemonClient.emitUtxoError(UnsatisfiedLinkError("missing native library"))
        waitUntil { daemonClient.closedUtxoStreams == 1 }

        assertThrows(MwebError.SyncFailure::class.java) {
            engine.sendInfo(MwebSendRequest.MwebToMweb(mwebDestination(), 50, 1), publicOptions())
        }
    }

    @Test
    fun send_mwebInput_marksSelectedUtxosSpent() = runBlocking {
        val addressCodec = MwebAddressCodec(LitecoinKit.NetworkType.MainNet)
        val destination = addressCodec.encode(fakeMwebPubkey(0x11), fakeMwebPubkey(0x22))
        val daemonClient = FakeDaemonClient(
            status = syncedStatus(10),
            streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, destination, 1, 100_000, 10, 1_000, spent = false)),
            dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
        )
        val engine = engineWith(daemonClient)
        engine.start()

        engine.send(MwebSendRequest.MwebToMweb(destination, 50, 1), publicOptions())

        assertTrue(engine.mwebUtxos().first().spent)
        assertEquals(MwebBalance(confirmed = 0, unconfirmed = 0), engine.balance)
    }

    @Test
    fun transactions_receiveUtxo_returnsIncoming() {
        val engine = engineWith(
            FakeDaemonClient(
                streamUtxos = listOf(MwebUtxo("receive-output", "receive-address", 2, 123, 10, 1_000, spent = false)),
            )
        )

        engine.start()
        waitUntil { engine.transactions().isNotEmpty() }

        val transaction = engine.transactions().single()
        assertEquals("mweb-incoming:receive-output", transaction.uid)
        assertEquals(MwebTransactionType.Incoming, transaction.type)
        assertEquals(MwebTransactionKind.Incoming, transaction.kind)
        assertEquals(123L, transaction.amount)
        assertEquals("receive-address", transaction.address)
        assertEquals(listOf("receive-output"), transaction.outputIds)
        assertEquals(10, transaction.height)
        assertEquals(1_000L, transaction.timestamp)
        assertFalse(transaction.pending)
    }

    @Test
    fun transactions_changeUtxo_doesNotReturnIncoming() {
        val engine = engineWith(
            FakeDaemonClient(
                streamUtxos = listOf(MwebUtxo("change-output", "", 0, 123, 10, 1_000, spent = false)),
            )
        )

        engine.start()
        waitUntil { engine.mwebUtxos().isNotEmpty() }

        assertTrue(engine.transactions().isEmpty())
    }

    @Test
    fun send_mwebToMweb_savesOutgoingTransaction() = runBlocking {
        val destination = mwebDestination()
        val engine = engineWith(
            FakeDaemonClient(
                status = syncedStatus(10),
                streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, destination, 1, 100_000, 10, 1_000, spent = false)),
                dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
            )
        )
        engine.start()

        engine.send(MwebSendRequest.MwebToMweb(destination, 50, 1), publicOptions())

        val transaction = engine.transactions().first { it.type == MwebTransactionType.Outgoing }
        assertEquals(MwebTransactionKind.MwebToMweb, transaction.kind)
        assertEquals(50L, transaction.amount)
        // Pure MWEB with recipient + change: kernel(3) + 2*standardOutput(18) = 39 wu * 100 = 3900 sat.
        assertEquals(3_900L, transaction.fee)
        assertEquals(destination, transaction.address)
        assertNull(transaction.canonicalTransactionHash)
        assertEquals(listOf("created-output"), transaction.outputIds)
        assertEquals(listOf(SELECTED_OUTPUT_ID), transaction.inputOutputIds)
        assertTrue(transaction.pending)
    }

    @Test
    fun send_mwebToMweb_utxoAboveDaemonUtxosHeight_throwsInsufficientMwebConfirmations() {
        val destination = mwebDestination()
        // The kit's storage already marks the UTXO confirmed at height 100, but the daemon's
        // mwebUtxosHeight still lags at 50. Spending it would make mwebd's Create fall through
        // ErrCoinNotFound and silently reclassify the MWEB input as a public one, which then
        // fails downstream as "MWEB daemon stopped unexpectedly" via the catch-all error mapper.
        val utxoHeight = 100
        val daemonMwebUtxosHeight = 50
        val daemonClient = FakeDaemonClient(
            status = statusWithUtxoHeight(
                chainHeight = utxoHeight,
                mwebUtxosHeight = daemonMwebUtxosHeight,
            ),
            streamUtxos = listOf(
                MwebUtxo(SELECTED_OUTPUT_ID, destination, 1, 100_000, utxoHeight, 1_000, spent = false),
            ),
            dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
        )
        val engine = engineWith(daemonClient)
        engine.start()

        assertThrows(MwebError.InsufficientMwebConfirmations::class.java) {
            runBlocking {
                engine.send(MwebSendRequest.MwebToMweb(destination, 50, 1), publicOptions())
            }
        }
        // The hidden UTXO must be rejected before any daemon call; otherwise mwebd's
        // ErrCoinNotFound branch would still reclassify it as a public input.
        assertTrue(daemonClient.createRequests.isEmpty())
        assertTrue(daemonClient.broadcastRawTransactions.isEmpty())
    }

    @Test
    fun send_mwebToMweb_utxoAtDaemonUtxosHeight_succeeds() = runBlocking {
        val destination = mwebDestination()
        val daemonMwebUtxosHeight = 100
        val daemonClient = FakeDaemonClient(
            status = statusWithUtxoHeight(
                chainHeight = daemonMwebUtxosHeight,
                mwebUtxosHeight = daemonMwebUtxosHeight,
            ),
            streamUtxos = listOf(
                MwebUtxo(SELECTED_OUTPUT_ID, destination, 1, 100_000, daemonMwebUtxosHeight, 1_000, spent = false),
            ),
            dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
        )
        val engine = engineWith(daemonClient)
        engine.start()

        engine.send(MwebSendRequest.MwebToMweb(destination, 50, 1), publicOptions())

        val transaction = engine.transactions().first { it.type == MwebTransactionType.Outgoing }
        assertEquals(MwebTransactionKind.MwebToMweb, transaction.kind)
        assertEquals(listOf(SELECTED_OUTPUT_ID), transaction.inputOutputIds)
    }

    @Test
    fun send_mwebToPublic_savesOutgoingTransactionWithoutCanonicalHashBeforeConfirmation() = runBlocking {
        val bridge = FakePublicTransactionBridge()
        val engine = engineWith(
            FakeDaemonClient(
                status = MwebDaemonStatus(MwebSyncState(100, 100, 100), nativeVersion = "test"),
                streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, "address", 1, 100_000, 95, 1_000, spent = false)),
                dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
            )
        )
        engine.start()

        engine.send(
            request = MwebSendRequest.MwebToPublic(PUBLIC_DESTINATION, 50, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = bridge,
        )

        val transaction = engine.transactions().first { it.type == MwebTransactionType.Outgoing }
        assertEquals(MwebTransactionKind.MwebToPublic, transaction.kind)
        assertEquals(50L, transaction.amount)
        assertEquals(PUBLIC_DESTINATION, transaction.address)
        assertNull(transaction.canonicalTransactionHash)
    }

    @Test
    fun refresh_pendingLocalSpentOutputAboveDaemonUtxoHeight_keepsTransactionPendingUntilCovered() = runBlocking {
        val destination = mwebDestination()
        val inputUtxo = receiveUtxo(outputId = SELECTED_OUTPUT_ID, value = 100_000)
        val daemonClient = FakeDaemonClient(
            status = syncedStatus(inputUtxo.height),
            streamUtxos = listOf(inputUtxo),
            dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
        )
        val engine = engineWith(daemonClient)
        engine.start()

        engine.send(MwebSendRequest.MwebToMweb(destination, 50, 1), publicOptions())
        val spentRequestsBefore = daemonClient.spentRequests.size
        daemonClient.spentOutputIds = listOf(inputUtxo.outputId)
        daemonClient.status = statusWithUtxoHeight(
            chainHeight = inputUtxo.height + 1,
            mwebUtxosHeight = inputUtxo.height - 1,
        )

        engine.refresh()

        assertEquals(spentRequestsBefore, daemonClient.spentRequests.size)
        assertTrue(engine.transactions().first { it.type == MwebTransactionType.Outgoing }.pending)

        daemonClient.status = statusWithUtxoHeight(
            chainHeight = inputUtxo.height + 1,
            mwebUtxosHeight = inputUtxo.height + 1,
            blockTime = 2_000,
        )
        engine.refresh()

        val transaction = engine.transactions().first { it.type == MwebTransactionType.Outgoing }
        assertEquals(listOf(listOf(inputUtxo.outputId)), daemonClient.spentRequests.drop(spentRequestsBefore))
        assertEquals(inputUtxo.height + 1, transaction.height)
        assertEquals(2_000L, transaction.timestamp)
        assertFalse(transaction.pending)
    }

    @Test
    fun refresh_mwebToPublicConfirmed_updatesCanonicalHashFromMwebBlock() = runBlocking {
        val bridge = FakePublicTransactionBridge()
        val daemonClient = FakeDaemonClient(
            status = MwebDaemonStatus(MwebSyncState(100, 100, 100), nativeVersion = "test"),
            streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, "address", 1, 100_000, 95, 1_000, spent = false)),
            dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
        )
        val hashProvider = FakeCanonicalTransactionHashProvider(mapOf(101 to "canonical-public-hash"))
        val engine = engineWith(daemonClient, canonicalTransactionHashProvider = hashProvider)
        engine.start()

        engine.send(
            request = MwebSendRequest.MwebToPublic(PUBLIC_DESTINATION, 50, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = bridge,
        )
        daemonClient.status = MwebDaemonStatus(
            syncState = MwebSyncState(101, 101, 101),
            nativeVersion = "test",
            blockTime = 2_000,
        )
        daemonClient.spentOutputIds = listOf(SELECTED_OUTPUT_ID)
        engine.refresh()

        waitUntil {
            engine.transactions()
                .any { it.kind == MwebTransactionKind.MwebToPublic && it.canonicalTransactionHash == "canonical-public-hash" }
        }
        val transaction = engine.transactions().first { it.kind == MwebTransactionKind.MwebToPublic }
        assertEquals("canonical-public-hash", transaction.canonicalTransactionHash)
        assertEquals(101, transaction.height)
        assertEquals(listOf(101), hashProvider.requests)
    }

    @Test
    fun send_publicToMweb_savesIncomingLocalTransaction() = runBlocking {
        val destination = mwebDestination()
        val signedTransaction = signedPublicTransaction()
        val bridge = FakePublicTransactionBridge(
            publicUtxos = listOf(publicUtxo(value = 5_000)),
            signedTransaction = signedTransaction,
        )
        val engine = engineWith(FakeDaemonClient(broadcastHash = DAEMON_BROADCAST_HASH))
        engine.start()

        engine.send(
            request = MwebSendRequest.PublicToMweb(destination, 1_000, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = bridge,
        )

        val transaction = engine.transactions().single()
        assertEquals(MwebTransactionType.Incoming, transaction.type)
        assertEquals(MwebTransactionKind.PublicToMweb, transaction.kind)
        assertEquals(1_000L, transaction.amount)
        assertEquals(destination, transaction.address)
        assertEquals(DAEMON_BROADCAST_HASH, transaction.canonicalTransactionHash)
        assertProcessedPublicTransactionHash(bridge, DAEMON_BROADCAST_HASH)
        assertEquals(listOf("created-output"), transaction.outputIds)
        assertTrue(transaction.pending)
    }

    @Test
    fun transactions_createdOutputFromOutgoing_doesNotReturnIncoming() = runBlocking {
        val destination = mwebDestination()
        val daemonClient = FakeDaemonClient(
            status = syncedStatus(10),
            streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, destination, 1, 100_000, 10, 1_000, spent = false)),
            dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
        )
        val engine = engineWith(daemonClient)
        engine.start()

        engine.send(MwebSendRequest.MwebToMweb(destination, 50, 1), publicOptions())
        daemonClient.emitUtxo(MwebUtxo("created-output", destination, 2, 50, 11, 1_100, spent = false))
        waitUntil { engine.mwebUtxos().any { it.outputId == "created-output" } }

        val transactions = engine.transactions()
        val outgoing = transactions.first { it.type == MwebTransactionType.Outgoing }
        assertFalse(transactions.any { it.uid == "mweb-incoming:created-output" })
        assertEquals(11, outgoing.height)
        assertEquals(1_100L, outgoing.timestamp)
        assertFalse(outgoing.pending)
    }

    @Test
    fun transactions_publicToMwebCreatedOutput_updatesLocalTransactionWithoutDuplicateIncoming() = runBlocking {
        val destination = mwebDestination()
        val bridge = FakePublicTransactionBridge(publicUtxos = listOf(publicUtxo(value = 5_000)))
        val daemonClient = FakeDaemonClient()
        val engine = engineWith(daemonClient)
        engine.start()

        engine.send(
            request = MwebSendRequest.PublicToMweb(destination, 1_000, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = bridge,
        )
        daemonClient.emitUtxo(MwebUtxo("created-output", destination, 2, 1_000, 11, 1_100, spent = false))
        waitUntil { engine.mwebUtxos().any { it.outputId == "created-output" } }

        val transactions = engine.transactions()
        val transaction = transactions.single()
        assertEquals(MwebTransactionType.Incoming, transaction.type)
        assertEquals(MwebTransactionKind.PublicToMweb, transaction.kind)
        assertEquals(11, transaction.height)
        assertEquals(1_100L, transaction.timestamp)
        assertFalse(transaction.pending)
    }

    @Test
    fun transactions_mwebToPublicWithoutCreatedMwebOutput_confirmsWhenSpentInputsConfirmed() = runBlocking {
        val bridge = FakePublicTransactionBridge()
        val confirmedStatus = MwebDaemonStatus(
            syncState = MwebSyncState(101, 101, 101),
            nativeVersion = "test",
            blockTime = 1_100,
        )
        val daemonClient = FakeDaemonClient(
            status = MwebDaemonStatus(MwebSyncState(100, 100, 100), nativeVersion = "test"),
            streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, "address", 1, 100_000, 95, 1_000, spent = false)),
            dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
            createdOutputIds = emptyList(),
        )
        val engine = engineWith(
            daemonClient = daemonClient,
        )
        engine.start()

        engine.send(
            request = MwebSendRequest.MwebToPublic(PUBLIC_DESTINATION, 50, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = bridge,
        )

        val transaction = engine.transactions().first { it.kind == MwebTransactionKind.MwebToPublic }
        assertEquals(MwebTransactionKind.MwebToPublic, transaction.kind)
        assertEquals(emptyList<String>(), transaction.outputIds)
        assertTrue(transaction.pending)

        daemonClient.status = confirmedStatus
        daemonClient.spentOutputIds = listOf(SELECTED_OUTPUT_ID)
        engine.refresh()

        val confirmedTransaction = engine.transactions().first { it.kind == MwebTransactionKind.MwebToPublic }
        assertEquals(101, confirmedTransaction.height)
        assertEquals(1_100L, confirmedTransaction.timestamp)
        assertFalse(confirmedTransaction.pending)
    }

    @Test
    fun refresh_mwebToPublicConfirmedSpentInputs_confirmsCreatedChangeOutput() = runBlocking {
        val bridge = FakePublicTransactionBridge()
        val confirmedStatus = MwebDaemonStatus(
            syncState = MwebSyncState(101, 101, 101),
            nativeVersion = "test",
            blockTime = 1_100,
        )
        val daemonClient = FakeDaemonClient(
            status = MwebDaemonStatus(MwebSyncState(100, 100, 100), nativeVersion = "test"),
            streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, "address", 1, 100_000, 95, 1_000, spent = false)),
            dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
            createdOutputIds = listOf("change-output"),
        )
        val engine = engineWith(daemonClient)
        engine.start()

        engine.send(
            request = MwebSendRequest.MwebToPublic(PUBLIC_DESTINATION, 50, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = bridge,
        )
        daemonClient.emitUtxo(MwebUtxo("change-output", "", 0, 90_000, 0, 0, spent = false))
        waitUntil { engine.balance == MwebBalance(confirmed = 0, unconfirmed = 90_000) }

        daemonClient.status = confirmedStatus
        daemonClient.spentOutputIds = listOf(SELECTED_OUTPUT_ID)
        engine.refresh()
        waitUntil { engine.balance == MwebBalance(confirmed = 90_000, unconfirmed = 0) }

        val changeOutput = engine.mwebUtxos().first { it.outputId == "change-output" }
        assertEquals(101, changeOutput.height)
        assertEquals(1_100L, changeOutput.blockTime)
        assertFalse(changeOutput.spent)
    }

    @Test
    fun transactions_stalePendingLocalTransaction_prunesTransactionAndPendingRaw() = runBlocking {
        var now = 1_000_000L
        val destination = mwebDestination()
        val engine = engineWith(
            daemonClient = FakeDaemonClient(
                status = syncedStatus(10),
                streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, destination, 1, 100_000, 10, 1_000, spent = false)),
                dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
            ),
            localTransactionTtlMillis = 1_000L,
            currentTimeMillisProvider = { now },
        )
        engine.start()
        engine.send(MwebSendRequest.MwebToMweb(destination, 50, 1), publicOptions())

        assertEquals(1, engine.transactions().count { it.type == MwebTransactionType.Outgoing })
        assertEquals(1, engine.pendingTransactions().size)

        now += 2_000L

        assertTrue(engine.transactions().none { it.type == MwebTransactionType.Outgoing })
        assertTrue(engine.pendingTransactions().isEmpty())
    }

    @Test
    fun sendInfo_mwebInput_usesLocalCanonicalFeeAndMwebOutpointTemplate() {
        val addressCodec = MwebAddressCodec(LitecoinKit.NetworkType.MainNet)
        val destination = addressCodec.encode(fakeMwebPubkey(0x11), fakeMwebPubkey(0x22))
        val confirmedValue = 100_000L
        val recipientValue = 50L
        val daemonClient = FakeDaemonClient(
            status = syncedStatus(10),
            streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, destination, 7, confirmedValue, 10, 1_000, spent = false)),
            // Daemon-stripped dry-run output value MUST NOT influence the fee in the new contract.
            dryRunRawTransaction = rawTransactionWithOutput(value = 999_999),
        )
        val engine = engineWith(daemonClient)
        engine.start()

        val sendInfo = engine.sendInfo(MwebSendRequest.MwebToMweb(destination, recipientValue, 1), publicOptions())
        val template = transactionSerializer.deserialize(
            BitcoinInputMarkable(daemonClient.createRequests.first().rawTransaction)
        )

        // Pure MWEB with recipient + change: kernel(3) + 2*standardOutput(18) = 39 wu * 100 = 3900 sat.
        val expectedMwebFee = 3_900L
        assertEquals(expectedMwebFee, sendInfo.mwebFee)
        assertEquals(confirmedValue - recipientValue - expectedMwebFee, sendInfo.changeValue)
        assertEquals(SELECTED_OUTPUT_ID, template.inputs.first().previousOutputTxHash.toHexString())
        assertEquals(7, template.inputs.first().previousOutputIndex)
        assertEquals(66, template.outputs.first().lockingScript.size)
        assertTrue(daemonClient.createRequests.any { it.dryRun })
    }

    @Test
    fun sendInfo_beforeStart_throwsSyncFailure() {
        val engine = engineWith(FakeDaemonClient())

        assertThrows(MwebError.SyncFailure::class.java) {
            engine.sendInfo(MwebSendRequest.MwebToMweb(mwebDestination(), 50, 1), publicOptions())
        }
    }

    @Test
    fun sendInfo_publicToMwebWithoutCanonicalUtxos_throwsInsufficientFunds() {
        val bridge = FakePublicTransactionBridge()
        val engine = engineWith(FakeDaemonClient())
        engine.start()

        assertThrows(MwebError.InsufficientFunds::class.java) {
            engine.sendInfo(
                request = MwebSendRequest.PublicToMweb(mwebDestination(), 50, 1),
                publicOptions = publicOptions(),
                publicTransactionBridge = bridge,
            )
        }
    }

    @Test
    fun sendInfo_mwebToPublicBelowSixConfirmations_throwsInsufficientConfirmations() {
        listOf(0, 96).forEach { utxoHeight ->
            val engine = engineWith(
                daemonClient = FakeDaemonClient(
                    status = MwebDaemonStatus(MwebSyncState(100, 100, 100), nativeVersion = "test"),
                    streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, "address", 1, 100_000, utxoHeight, 1_000, spent = false)),
                    dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
                ),
            )
            engine.start()

            assertThrows(MwebError.InsufficientMwebConfirmations::class.java) {
                engine.sendInfo(
                    request = MwebSendRequest.MwebToPublic(PUBLIC_DESTINATION, 50, 1),
                    publicOptions = publicOptions(),
                    publicTransactionBridge = FakePublicTransactionBridge(),
                )
            }
        }
    }

    @Test
    fun sendInfo_mwebToPublicSixOrMoreConfirmations_selectsMwebUtxo() {
        listOf(95, 94).forEach { utxoHeight ->
            val bridge = FakePublicTransactionBridge()
            val engine = engineWith(
                daemonClient = FakeDaemonClient(
                    status = MwebDaemonStatus(MwebSyncState(100, 100, 100), nativeVersion = "test"),
                    streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, "address", 1, 100_000, utxoHeight, 1_000, spent = false)),
                    dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
                ),
            )
            engine.start()

            val sendInfo = engine.sendInfo(
                request = MwebSendRequest.MwebToPublic(PUBLIC_DESTINATION, 50, 1),
                publicOptions = publicOptions(),
                publicTransactionBridge = bridge,
            )

            assertEquals(listOf(utxoHeight), sendInfo.selectedMwebUtxos.map { it.height })
            assertTrue(bridge.outputCalls.isNotEmpty())
        }
    }

    @Test
    fun send_publicToMweb_signsPublicInputsAndBroadcastsSignedRaw() = runBlocking {
        val signedRaw = byteArrayOf(9, 8, 7, 6)
        val signedTransaction = signedPublicTransaction()
        val bridge = FakePublicTransactionBridge(
            publicUtxos = listOf(publicUtxo(value = 5_000)),
            signedRawTransaction = signedRaw,
            signedTransaction = signedTransaction,
        )
        val daemonClient = FakeDaemonClient(
            broadcastHash = DAEMON_BROADCAST_HASH,
            createRawTransaction = ::mwebCreateResponseForPublicPegIn,
        )
        val engine = engineWith(daemonClient)
        engine.start()

        val result = engine.send(
            request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = bridge,
        )

        assertEquals(1, bridge.signCalls.size)
        assertEquals(listOf(signedTransaction), bridge.processCreatedTransactions)
        assertProcessedPublicTransactionHash(bridge, DAEMON_BROADCAST_HASH)
        assertPublicPegInRawToSign(bridge.signCalls.single().rawTransaction, recipientValue = 1_000, feeRate = 1)
        assertArrayEquals(signedRaw, daemonClient.broadcastRawTransactions.single())
        assertArrayEquals(signedRaw, result.rawTransaction)
        assertEquals(DAEMON_BROADCAST_HASH, result.canonicalTransactionHash)
    }

    @Test
    fun send_publicToMwebDefaultSigner_processesSingleCanonicalPublicTransaction() = runBlocking {
        val bridge = FakePublicTransactionBridge(publicUtxos = listOf(publicUtxo(value = 5_000)))
        val daemonClient = FakeDaemonClient(
            createRawTransaction = ::mwebCreateResponseForPublicPegIn,
            broadcastHashProvider = { rawTransaction ->
                transactionSerializer.deserialize(BitcoinInputMarkable(rawTransaction)).header.hash.toReversedHex()
            },
        )
        val engine = engineWith(daemonClient)
        engine.start()

        val result = engine.send(
            request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = bridge,
        )

        val processedTransaction = bridge.processCreatedTransactions.single()
        assertEquals(result.canonicalTransactionHash, processedTransaction.header.hash.toReversedHex())
        assertEquals(Transaction.Status.NEW, processedTransaction.header.status)
        assertNull(processedTransaction.header.conflictingTxHash)
    }

    @Test
    fun send_publicToMwebProcessCreatedCanReadMwebState_doesNotDeadlock() = runBlocking {
        var pendingTransactionCount: Int? = null
        lateinit var engine: LitecoinMwebEngine
        val bridge = FakePublicTransactionBridge(
            publicUtxos = listOf(publicUtxo(value = 5_000)),
            onProcessCreated = {
                pendingTransactionCount = engine.pendingTransactions().size
            },
        )
        val daemonClient = FakeDaemonClient()
        engine = engineWith(
            daemonClient = daemonClient,
            dispatcherProvider = CoroutineMwebDispatcherProvider(
                io = Dispatchers.Default,
                callback = ImmediateDispatcher,
            ),
        )
        engine.start()

        withTimeout(1_000) {
            engine.send(
                request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
                publicOptions = publicOptions(),
                publicTransactionBridge = bridge,
            )
        }

        assertEquals(1, pendingTransactionCount)
    }

    @Test
    fun send_publicToMwebProcessCreatedFails_stillReturnsResultAndPersistsPending() = runBlocking {
        val bridge = FakePublicTransactionBridge(
            publicUtxos = listOf(publicUtxo(value = 5_000)),
            onProcessCreated = { throw IllegalStateException("public-side persistence failed") },
        )
        val engine = engineWith(daemonClient = FakeDaemonClient(broadcastHash = DAEMON_BROADCAST_HASH))
        engine.start()

        val result = withTimeout(1_000) {
            engine.send(
                request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
                publicOptions = publicOptions(),
                publicTransactionBridge = bridge,
            )
        }

        assertEquals(DAEMON_BROADCAST_HASH, result.canonicalTransactionHash)
        assertProcessedPublicTransactionHash(bridge, DAEMON_BROADCAST_HASH)
        assertEquals(1, engine.pendingTransactions().size)
    }

    @Test
    fun send_publicToMwebBroadcastFailure_doesNotPersistPublicTransaction() {
        val bridge = FakePublicTransactionBridge(publicUtxos = listOf(publicUtxo(value = 5_000)))
        val daemonClient = FakeDaemonClient(broadcastError = IllegalStateException("broadcast failed"))
        val engine = engineWith(daemonClient)
        engine.start()

        assertPublicPegInBroadcastFailureDoesNotPersist(bridge) {
            engine.send(
                request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
                publicOptions = publicOptions(),
                publicTransactionBridge = bridge,
            )
        }
    }

    @Test
    fun publicPegInSender_sendInfoWithoutEngine_preparesPublicToMweb() {
        val daemonClient = FakeDaemonClient()
        val sender = publicPegInSender(daemonClient)
        val bridge = FakePublicTransactionBridge(publicUtxos = listOf(publicUtxo(value = 5_000)))

        val sendInfo = sender.sendInfo(
            request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = bridge,
        )

        assertEquals(1, sendInfo.selectedPublicUtxos.size)
        assertTrue(sendInfo.selectedMwebUtxos.isEmpty())
        assertTrue(daemonClient.createRequests.any { it.dryRun })
    }

    @Test
    fun publicPegInSender_sendWithoutEngine_signsAndBroadcastsPublicToMweb() = runBlocking {
        val signedRaw = byteArrayOf(9, 8, 7, 6)
        val signedTransaction = signedPublicTransaction()
        val daemonClient = FakeDaemonClient(
            broadcastHash = DAEMON_BROADCAST_HASH,
            createRawTransaction = ::mwebCreateResponseForPublicPegIn,
        )
        val sender = publicPegInSender(daemonClient)
        val bridge = FakePublicTransactionBridge(
            publicUtxos = listOf(publicUtxo(value = 5_000)),
            signedRawTransaction = signedRaw,
            signedTransaction = signedTransaction,
        )

        val result = sender.send(
            request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = bridge,
        )

        assertEquals(DAEMON_BROADCAST_HASH, result.canonicalTransactionHash)
        assertEquals(listOf("created-output"), result.outputIds)
        assertEquals(1, bridge.signCalls.size)
        assertPublicPegInRawToSign(bridge.signCalls.single().rawTransaction, recipientValue = 1_000, feeRate = 1)
        assertEquals(1, daemonClient.startCount)
        assertArrayEquals(signedRaw, daemonClient.broadcastRawTransactions.single())
        assertEquals(listOf(signedTransaction), bridge.processCreatedTransactions)
        assertProcessedPublicTransactionHash(bridge, DAEMON_BROADCAST_HASH)
    }

    @Test
    fun publicPegInSender_broadcastFailure_doesNotPersistPublicTransaction() {
        val daemonClient = FakeDaemonClient(broadcastError = IllegalStateException("broadcast failed"))
        val sender = publicPegInSender(daemonClient)
        val bridge = FakePublicTransactionBridge(publicUtxos = listOf(publicUtxo(value = 5_000)))

        assertPublicPegInBroadcastFailureDoesNotPersist(bridge) {
            sender.send(
                request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
                publicOptions = publicOptions(),
                publicTransactionBridge = bridge,
            )
        }
    }

    @Test
    fun publicPegInSender_signedPublicTransactionWithoutInputs_failsBeforeBroadcastAndPersist() {
        val daemonClient = FakeDaemonClient()
        val sender = publicPegInSender(daemonClient)
        val malformedSignedTransaction = transactionSerializer.deserialize(
            BitcoinInputMarkable(rawTransactionWithOutput(value = 1_000))
        )
        val bridge = FakePublicTransactionBridge(
            publicUtxos = listOf(publicUtxo(value = 5_000)),
            signedTransaction = malformedSignedTransaction,
        )

        assertThrows(MwebError.SyncFailure::class.java) {
            runBlocking {
                sender.send(
                    request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
                    publicOptions = publicOptions(),
                    publicTransactionBridge = bridge,
                )
            }
        }

        assertEquals(1, bridge.signCalls.size)
        assertTrue(daemonClient.broadcastRawTransactions.isEmpty())
        assertEquals(0, bridge.processCreatedCount)
    }

    @Test
    fun publicPegInSender_concurrentSendInfo_serializesOperations() = runBlocking {
        val sender = publicPegInSender(FakeDaemonClient())
        val bridge = FakePublicTransactionBridge(
            publicUtxos = listOf(publicUtxo(value = 5_000)),
            spendableDelayMillis = 50,
        )
        val jobs = List(2) {
            async(Dispatchers.Default) {
                sender.sendInfo(
                    request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
                    publicOptions = publicOptions(),
                    publicTransactionBridge = bridge,
                )
            }
        }

        jobs.awaitAll()

        assertEquals(1, bridge.maxConcurrentSpendableCalls)
    }

    @Test
    fun publicPegInSender_clearWhileStarted_throwsUntilStopped() {
        val walletId = walletId("mweb-public-pegin-clear-test")
        val sender = publicPegInSender(FakeDaemonClient(), walletId)
        val bridge = FakePublicTransactionBridge(publicUtxos = listOf(publicUtxo(value = 5_000)))
        sender.sendInfo(
            request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = bridge,
        )

        assertThrows(IllegalStateException::class.java) {
            MwebFiles.clear(context, LitecoinKit.NetworkType.MainNet, walletId)
        }

        sender.stop()
        MwebFiles.clear(context, LitecoinKit.NetworkType.MainNet, walletId)
    }

    @Test
    fun publicPegInSender_stopDeletesPublicSendDataDir() {
        val walletId = walletId("mweb-public-pegin-stop-test")
        val sender = publicPegInSender(FakeDaemonClient(), walletId)
        val bridge = FakePublicTransactionBridge(publicUtxos = listOf(publicUtxo(value = 5_000)))
        sender.sendInfo(
            request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = bridge,
        )
        val dataDir = MwebFiles.publicSendDaemonDataDir(context, LitecoinKit.NetworkType.MainNet, walletId)
        dataDir.mkdirs()

        sender.stop()

        assertFalse(dataDir.exists())
    }

    @Test
    fun publicPegInSender_daemonCrashDropsClientForNextAttempt() {
        val walletId = walletId("mweb-public-pegin-crash-test")
        val crashedClient = FakeDaemonClient(createError = IllegalStateException("boom"))
        val recoveredClient = FakeDaemonClient()
        val clients = mutableListOf(crashedClient, recoveredClient)
        val sender = MwebPublicPegInSender(
            context = context,
            walletId = walletId,
            networkType = LitecoinKit.NetworkType.MainNet,
            addressCodec = MwebAddressCodec(LitecoinKit.NetworkType.MainNet),
            config = MwebPublicSendConfig(
                dispatcherProvider = dispatcherProvider,
                daemonClientFactory = { clients.removeAt(0) },
            ),
        ).also(publicPegInSenders::add)
        val bridge = FakePublicTransactionBridge(publicUtxos = listOf(publicUtxo(value = 5_000)))

        assertThrows(MwebError.DaemonCrashed::class.java) {
            sender.sendInfo(
                request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
                publicOptions = publicOptions(),
                publicTransactionBridge = bridge,
            )
        }

        val sendInfo = sender.sendInfo(
            request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = bridge,
        )

        assertEquals(1, sendInfo.selectedPublicUtxos.size)
        assertEquals(1, crashedClient.stopCount)
        assertEquals(1, recoveredClient.startCount)
    }

    @Test
    fun sendInfo_publicToMwebWithoutCallerBridge_throwsNativeUnavailable() {
        val engine = engineWith(FakeDaemonClient())
        engine.start()

        assertThrows(MwebError.NativeUnavailable::class.java) {
            engine.sendInfo(
                request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
                publicOptions = publicOptions(),
            )
        }
    }

    @Test
    fun sendInfo_publicToMwebCallerBridge_usesCallerBridge() {
        val callerBridge = FakePublicTransactionBridge(
            publicUtxos = listOf(publicUtxo(value = 5_000)),
        )
        val engine = engineWith(FakeDaemonClient())
        engine.start()

        val sendInfo = engine.sendInfo(
            request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = callerBridge,
        )

        assertEquals(1, sendInfo.selectedPublicUtxos.size)
        assertEquals(1, callerBridge.spendableCalls)
    }

    private fun engineWith(
        daemonClient: MwebDaemonClient,
        spentPollIntervalMillis: Long = 60_000L,
        statusPollIntervalMillis: Long = 60_000L,
        localTransactionTtlMillis: Long = 24 * 60 * 60 * 1_000L,
        currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() },
        canonicalTransactionHashProvider: MwebCanonicalTransactionHashProvider = EmptyMwebCanonicalTransactionHashProvider,
        dispatcherProvider: MwebDispatcherProvider = this.dispatcherProvider,
        restorePoint: MwebRestorePoint = MwebRestorePoint.Activation,
        replayCompleteTimeoutMillis: Long = MwebUtxoSynchronizer.DEFAULT_REPLAY_COMPLETE_TIMEOUT_MILLIS,
        streamHealthyThresholdMillis: Long = MwebUtxoSynchronizer.DEFAULT_STREAM_HEALTHY_THRESHOLD_MILLIS,
        restoreCheckpointProvider: (LitecoinKit.NetworkType, Int) -> String? = { _, _ -> null },
        daemonClientFactory: (MwebDaemonConfig) -> MwebDaemonClient = { daemonClient },
    ): LitecoinMwebEngine {
        val walletId = "mweb-test-${System.nanoTime()}"
        walletIds.add(walletId)
        val engine = LitecoinMwebEngine(
            context = context,
            seed = ByteArray(32),
            walletId = walletId,
            dispatcherProvider = dispatcherProvider,
            restorePoint = restorePoint,
            daemonClientFactory = { config -> daemonClientFactory(config) },
            spentPollIntervalMillis = spentPollIntervalMillis,
            statusPollIntervalMillis = statusPollIntervalMillis,
            replayCompleteTimeoutMillis = replayCompleteTimeoutMillis,
            streamHealthyThresholdMillis = streamHealthyThresholdMillis,
            localTransactionTtlMillis = localTransactionTtlMillis,
            currentTimeMillisProvider = currentTimeMillisProvider,
            canonicalTransactionHashProvider = canonicalTransactionHashProvider,
            restoreCheckpointProvider = restoreCheckpointProvider,
        )
        engines.add(engine)
        return engine
    }

    private class FakeDaemonClient(
        status: MwebDaemonStatus = MwebDaemonStatus(MwebSyncState(0, 0, 0), nativeVersion = "test"),
        private val completeStatus: MwebDaemonStatus? = null,
        private val completeUtxoStream: Boolean = false,
        private val completeEveryUtxoStream: Boolean = false,
        private val replayCompleteHeight: Int? = null,
        private val startError: Throwable? = null,
        private val streamUtxos: List<MwebUtxo> = emptyList(),
        spentOutputIds: List<String> = emptyList(),
        private val dryRunRawTransaction: ByteArray? = null,
        private val createdOutputIds: List<String> = listOf("created-output"),
        private val createError: Throwable? = null,
        private val broadcastError: Throwable? = null,
        private val broadcastHash: String = DAEMON_BROADCAST_HASH,
        private val broadcastHashProvider: (ByteArray) -> String = { broadcastHash },
        private val createRawTransaction: (ByteArray, Int, Boolean) -> ByteArray = { rawTransaction, _, dryRun ->
            dryRunRawTransaction?.takeIf { dryRun } ?: rawTransaction
        },
    ) : MwebDaemonClient {
        private val addressCodec = MwebAddressCodec(LitecoinKit.NetworkType.MainNet)
        var status: MwebDaemonStatus = status
        var spentOutputIds: List<String> = spentOutputIds
        val utxoFromHeights = mutableListOf<Int>()
        val createRequests = mutableListOf<CreateRequest>()
        val broadcastRawTransactions = mutableListOf<ByteArray>()
        var startCount = 0
            private set
        var closedUtxoStreams = 0
            private set
        var stopCount = 0
            private set
        val spentRequests = mutableListOf<List<String>>()
        private var streamed = false
        private var streamCompleted = false
        private var utxoHandler: ((MwebUtxo) -> Unit)? = null
        private var utxoErrorHandler: ((Throwable) -> Unit)? = null
        private var utxoCompleteHandler: (() -> Unit)? = null

        override fun start(statusTimeoutMillis: Long): MwebDaemonStatus {
            startCount += 1
            startError?.let { throw it }
            return status
        }

        override fun stop() {
            stopCount += 1
        }

        override fun status(statusTimeoutMillis: Long): MwebDaemonStatus {
            return if (streamCompleted) completeStatus ?: status else status
        }

        override fun addresses(fromIndex: Int, toIndex: Int): List<String> {
            return (fromIndex..toIndex).map { index ->
                addressCodec.encode(
                    scanPublicKey = compressedFakePubkey(prefix = 0x02, seed = (index + 1).toByte()),
                    spendPublicKey = compressedFakePubkey(prefix = 0x03, seed = (index + 34).toByte()),
                )
            }
        }

        private fun compressedFakePubkey(prefix: Byte, seed: Byte): ByteArray {
            return ByteArray(33).also { bytes ->
                bytes[0] = prefix
                for (i in 1 until 33) bytes[i] = (seed + i).toByte()
            }
        }

        override fun utxos(
            fromHeight: Int,
            onUtxo: (MwebUtxo) -> Unit,
            onReplayComplete: (Int) -> Unit,
            onComplete: () -> Unit,
            onError: (Throwable) -> Unit,
        ): Closeable {
            utxoFromHeights.add(fromHeight)
            utxoHandler = onUtxo
            utxoErrorHandler = onError
            utxoCompleteHandler = onComplete
            if (!streamed) {
                streamUtxos.forEach(onUtxo)
                replayCompleteHeight?.let(onReplayComplete)
                if (completeUtxoStream) {
                    streamCompleted = true
                    onComplete()
                }
                streamed = true
            }
            if (completeEveryUtxoStream) {
                streamCompleted = true
                onComplete()
            }
            return Closeable { closedUtxoStreams += 1 }
        }

        fun emitUtxo(utxo: MwebUtxo) {
            utxoHandler?.invoke(utxo)
        }

        fun emitUtxoError(error: Throwable) {
            utxoErrorHandler?.invoke(error)
        }

        fun emitUtxoErrorAndComplete(error: Throwable) {
            utxoErrorHandler?.invoke(error)
            utxoCompleteHandler?.invoke()
        }

        override fun spent(outputIds: List<String>): List<String> {
            spentRequests.add(outputIds)
            return spentOutputIds.filter { it in outputIds }
        }

        override fun create(rawTransaction: ByteArray, feeRate: Int, dryRun: Boolean): MwebCreateResult {
            createRequests.add(CreateRequest(rawTransaction, dryRun))
            createError?.let { throw it }
            return MwebCreateResult(
                rawTransaction = createRawTransaction(rawTransaction, feeRate, dryRun),
                outputIds = createdOutputIds,
            )
        }

        override fun broadcast(rawTransaction: ByteArray): String {
            broadcastRawTransactions.add(rawTransaction.copyOf())
            broadcastError?.let { throw it }
            return broadcastHashProvider(rawTransaction)
        }
    }

    private class FakeCanonicalTransactionHashProvider(
        private val hashes: Map<Int, String>,
    ) : MwebCanonicalTransactionHashProvider {
        val requests = mutableListOf<Int>()

        override suspend fun transactionHash(height: Int): String? {
            requests += height
            return hashes[height]
        }
    }

    private class FakePublicTransactionBridge(
        private val publicUtxos: List<UnspentOutput> = emptyList(),
        private val signedRawTransaction: ByteArray? = null,
        private val spendableDelayMillis: Long = 0,
        private val signedTransaction: FullTransaction? = null,
        private val onProcessCreated: (() -> Unit)? = null,
    ) : MwebPublicTransactionBridge {
        private val transactionSerializer = LitecoinTransactionSerializer()
        val signCalls = mutableListOf<SignCall>()
        val outputCalls = mutableListOf<String>()
        var maxConcurrentSpendableCalls = 0
            private set
        private var activeSpendableCalls = 0
        var spendableCalls = 0
            private set
        var processCreatedCount = 0
            private set
        val processCreatedTransactions = mutableListOf<FullTransaction>()

        override fun spendableUtxos(options: MwebPublicSendOptions): List<UnspentOutput> {
            synchronized(this) {
                spendableCalls += 1
                activeSpendableCalls += 1
                maxConcurrentSpendableCalls = maxOf(maxConcurrentSpendableCalls, activeSpendableCalls)
            }
            return try {
                if (spendableDelayMillis > 0) {
                    Thread.sleep(spendableDelayMillis)
                }
                publicUtxos
            } finally {
                synchronized(this) {
                    activeSpendableCalls -= 1
                }
            }
        }

        override fun output(value: Long, address: String): TransactionOutput {
            outputCalls.add(address)
            return TransactionOutput(
                value = value,
                index = 0,
                script = byteArrayOf(0),
                type = ScriptType.UNKNOWN,
                address = address,
            )
        }

        override fun changeOutput(
            value: Long,
            selectedUtxos: List<UnspentOutput>,
            changeToFirstInput: Boolean,
        ): TransactionOutput {
            return TransactionOutput(
                value = value,
                index = 0,
                script = byteArrayOf(1),
                type = ScriptType.UNKNOWN,
                address = "public-change",
            )
        }

        override fun serialize(transaction: FullTransaction): ByteArray {
            return signedRawTransaction?.copyOf() ?: transactionSerializer.serialize(transaction)
        }

        override fun processCreated(transaction: FullTransaction): FullTransaction {
            processCreatedCount += 1
            processCreatedTransactions += transaction
            onProcessCreated?.invoke()
            return transaction
        }

        override suspend fun sign(rawTransaction: ByteArray, selectedUtxos: List<UnspentOutput>): FullTransaction {
            signCalls.add(SignCall(rawTransaction.copyOf(), selectedUtxos))
            return signedTransaction ?: signedNewTransaction(rawTransaction)
        }

        private fun signedNewTransaction(rawTransaction: ByteArray): FullTransaction {
            return transactionSerializer.deserialize(BitcoinInputMarkable(rawTransaction)).also {
                it.header.status = Transaction.Status.NEW
            }
        }
    }

    private class CreateRequest(
        val rawTransaction: ByteArray,
        val dryRun: Boolean,
    )

    private class SignCall(
        val rawTransaction: ByteArray,
        val selectedUtxos: List<UnspentOutput>,
    )

    private fun publicOptions(): MwebPublicSendOptions {
        return MwebPublicSendOptions(
            unspentOutputs = null,
            changeToFirstInput = false,
            rbfEnabled = false,
            filters = UtxoFilters(),
        )
    }

    private fun signedPublicTransaction(): FullTransaction {
        return FullTransaction(
            header = Transaction(version = 2, lockTime = 0),
            inputs = listOf(
                TransactionInput(
                    previousOutputTxHash = ByteArray(32) { (it + 1).toByte() },
                    previousOutputIndex = 0,
                    sequence = 0xffff_ffff,
                )
            ),
            outputs = emptyList(),
            transactionSerializer = transactionSerializer,
        ).also { transaction ->
            transaction.setHash(ByteArray(32) { (it + 64).toByte() })
        }
    }

    private fun publicPegInSender(
        daemonClient: FakeDaemonClient,
        walletId: String = walletId("mweb-public-pegin-test"),
    ): MwebPublicPegInSender {
        return MwebPublicPegInSender(
            context = context,
            walletId = walletId,
            networkType = LitecoinKit.NetworkType.MainNet,
            addressCodec = MwebAddressCodec(LitecoinKit.NetworkType.MainNet),
            config = MwebPublicSendConfig(
                dispatcherProvider = dispatcherProvider,
                daemonClientFactory = { daemonClient },
            ),
        ).also(publicPegInSenders::add)
    }

    private fun assertPublicPegInBroadcastFailureDoesNotPersist(
        bridge: FakePublicTransactionBridge,
        send: suspend () -> Unit,
    ) {
        assertThrows(MwebError.DaemonCrashed::class.java) {
            runBlocking { send() }
        }

        assertEquals(1, bridge.signCalls.size)
        assertEquals(0, bridge.processCreatedCount)
    }

    private fun assertProcessedPublicTransactionHash(
        bridge: FakePublicTransactionBridge,
        expectedHash: String,
    ) {
        assertEquals(1, bridge.processCreatedCount)
        assertEquals(expectedHash, bridge.processCreatedTransactions.single().header.hash.toReversedHex())
    }

    private fun walletId(prefix: String): String {
        return "$prefix-${System.nanoTime()}".also(walletIds::add)
    }

    private object ImmediateDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }
    }

    private fun rawTransactionWithOutput(value: Long): ByteArray {
        val output = TransactionOutput(
            value = value,
            index = 0,
            script = byteArrayOf(1),
            type = ScriptType.UNKNOWN,
        )
        return transactionSerializer.serialize(
            FullTransaction(
                header = mwebTransactionHeader(),
                inputs = emptyList(),
                outputs = listOf(output),
                transactionSerializer = transactionSerializer,
            )
        )
    }

    private fun rawTransactionWithoutPublicOutputs(): ByteArray {
        return transactionSerializer.serialize(
            FullTransaction(
                header = mwebTransactionHeader(),
                inputs = emptyList(),
                outputs = emptyList(),
                transactionSerializer = transactionSerializer,
            )
        )
    }

    private fun mwebCreateResponseForPublicPegIn(
        rawTransaction: ByteArray,
        feeRate: Int,
        dryRun: Boolean,
    ): ByteArray {
        if (dryRun) return rawTransaction

        val template = transactionSerializer.deserialize(BitcoinInputMarkable(rawTransaction))
        val markerValue = template.outputs.sumOf { it.value } +
            MwebFeeFormula.estimate(template.outputs, feeRate, isPegIn = true)
        val markerOutput = TransactionOutput(
            value = markerValue,
            index = 0,
            script = ByteArray(34),
            type = ScriptType.UNKNOWN,
        )
        return transactionSerializer.serialize(
            FullTransaction(
                header = mwebTransactionHeader(),
                inputs = template.inputs,
                outputs = listOf(markerOutput),
                transactionSerializer = transactionSerializer,
            )
        )
    }

    private fun assertPublicPegInRawToSign(rawTransaction: ByteArray, recipientValue: Long, feeRate: Int) {
        val rawToSign = transactionSerializer.deserialize(BitcoinInputMarkable(rawTransaction))
        val expectedMwebFee = MwebFeeFormula.estimate(listOf(mwebTemplateOutput(recipientValue)), feeRate, isPegIn = true)

        assertEquals(2, rawToSign.outputs.size)
        assertEquals(recipientValue + expectedMwebFee, rawToSign.outputs[0].value)
        assertEquals(34, rawToSign.outputs[0].lockingScript.size)
        assertArrayEquals(byteArrayOf(1), rawToSign.outputs[1].lockingScript)
    }

    private fun mwebTemplateOutput(value: Long): TransactionOutput {
        return TransactionOutput(
            value = value,
            index = 0,
            script = fakeMwebPubkey(0x11) + fakeMwebPubkey(0x22),
            type = ScriptType.UNKNOWN,
        )
    }

    private fun mwebTransactionHeader(): Transaction {
        return Transaction(version = 2, lockTime = 0).apply {
            extraPayload = byteArrayOf(1)
        }
    }

    private fun mwebDestination(): String {
        return MwebAddressCodec(LitecoinKit.NetworkType.MainNet)
            .encode(fakeMwebPubkey(0x11), fakeMwebPubkey(0x22))
    }

    private fun fakeMwebPubkey(seed: Byte): ByteArray {
        // Compressed secp256k1 pubkeys start with 0x02 or 0x03; only the prefix
        // byte is checked by MwebFeeFormula.isMwebOutput.
        return ByteArray(33).also { bytes ->
            bytes[0] = 0x02
            for (i in 1 until 33) bytes[i] = seed
        }
    }

    private fun waitUntil(timeoutMillis: Long = 1_000, condition: () -> Boolean) = runBlocking {
        withTimeout(timeoutMillis) {
            while (!condition()) {
                delay(10)
            }
        }
    }

    private fun receiveUtxo(
        outputId: String = "receive-output",
        value: Long = 100,
        height: Int = 10,
    ) = MwebUtxo(
        outputId = outputId,
        address = "address",
        addressIndex = 1,
        value = value,
        height = height,
        blockTime = 1_000,
        spent = false,
    )

    private fun changeUtxo(
        outputId: String = "change-output",
        value: Long = 100,
        height: Int = 10,
    ) = MwebUtxo(
        outputId = outputId,
        address = "",
        addressIndex = 0,
        value = value,
        height = height,
        blockTime = 1_000,
        spent = false,
    )

    private fun syncedStatus(height: Int) = statusWithUtxoHeight(
        chainHeight = height,
        mwebUtxosHeight = height,
    )

    private fun statusWithUtxoHeight(
        chainHeight: Int,
        mwebUtxosHeight: Int,
        blockTime: Long = 0,
    ) = MwebDaemonStatus(
        syncState = MwebSyncState(
            blockHeaderHeight = chainHeight,
            mwebHeaderHeight = chainHeight,
            mwebUtxosHeight = mwebUtxosHeight,
        ),
        nativeVersion = "test-native",
        blockTime = blockTime,
    )

    private fun publicUtxo(value: Long): UnspentOutput {
        val transaction = Transaction(version = 2, lockTime = 0).apply {
            hash = ByteArray(32) { (it + 1).toByte() }
        }
        val publicKey = PublicKey(
            account = 0,
            index = 0,
            external = true,
            publicKey = ByteArray(33) { 2 },
            publicKeyHash = ByteArray(20) { 3 },
        )
        val output = TransactionOutput(
            value = value,
            index = 0,
            script = byteArrayOf(0),
            type = ScriptType.P2WPKH,
            address = "public-source",
            publicKey = publicKey,
        ).apply {
            transactionHash = transaction.hash
        }
        return UnspentOutput(output, publicKey, transaction, block = null)
    }

    private companion object {
        const val SELECTED_OUTPUT_ID = "0102030405060708091011121314151617181920212223242526272829303132"
        const val PUBLIC_DESTINATION = "ltc1q9z5mzd0k72k8f8g9cny70a4rvv7ne48x336jw5"
        private const val DAEMON_BROADCAST_HASH =
            "451282ec7da9766661d540f460987fb35c5b330be7276f111b27fe8909f23fe8"
    }
}

package io.horizontalsystems.litecoinkit.mweb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressCodec
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClient
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonConfig
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonStatus
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebCreateResult
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val ioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val dispatcherProvider = CoroutineMwebDispatcherProvider(io = ioDispatcher, callback = ImmediateDispatcher)
    private val transactionSerializer = BaseTransactionSerializer()

    @After
    fun tearDown() {
        engines.forEach { engine -> engine.dispose() }
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
    fun refresh_spentOutputs_marksSpentAndUpdatesBalance() {
        val daemonClient = FakeDaemonClient(
            streamUtxos = listOf(MwebUtxo("spent-output", "address", 1, 100, 10, 1_000, spent = false)),
            spentOutputIds = listOf("spent-output"),
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
    fun spentPoll_started_marksSpentOutputsPeriodically() {
        val daemonClient = FakeDaemonClient(
            streamUtxos = listOf(MwebUtxo("spent-output", "address", 1, 100, 10, 1_000, spent = false)),
        )
        val engine = engineWith(daemonClient, spentPollIntervalMillis = 10)

        engine.start()
        waitUntil { engine.mwebUtxos().isNotEmpty() }
        daemonClient.spentOutputIds = listOf("spent-output")
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
        val destination = addressCodec.encode(ByteArray(33) { 1 }, ByteArray(33) { 2 })
        val daemonClient = FakeDaemonClient(
            streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, destination, 1, 100, 10, 1_000, spent = false)),
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
                streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, destination, 1, 100, 10, 1_000, spent = false)),
                dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
            )
        )
        engine.start()

        engine.send(MwebSendRequest.MwebToMweb(destination, 50, 1), publicOptions())

        val transaction = engine.transactions().first { it.type == MwebTransactionType.Outgoing }
        assertEquals(MwebTransactionKind.MwebToMweb, transaction.kind)
        assertEquals(50L, transaction.amount)
        assertEquals(0L, transaction.fee)
        assertEquals(destination, transaction.address)
        assertEquals("test-transaction", transaction.canonicalTransactionHash)
        assertEquals(listOf("created-output"), transaction.outputIds)
        assertEquals(listOf(SELECTED_OUTPUT_ID), transaction.inputOutputIds)
        assertTrue(transaction.pending)
    }

    @Test
    fun send_mwebToPublic_savesOutgoingTransactionWithCanonicalHash() = runBlocking {
        val bridge = FakePublicTransactionBridge()
        val engine = engineWith(
            FakeDaemonClient(
                status = MwebDaemonStatus(MwebSyncState(100, 100, 100), nativeVersion = "test"),
                streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, "address", 1, 100, 95, 1_000, spent = false)),
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
        assertEquals("test-transaction", transaction.canonicalTransactionHash)
    }

    @Test
    fun send_publicToMweb_savesIncomingLocalTransaction() = runBlocking {
        val destination = mwebDestination()
        val bridge = FakePublicTransactionBridge(publicUtxos = listOf(publicUtxo(value = 5_000)))
        val engine = engineWith(FakeDaemonClient())
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
        assertEquals("test-transaction", transaction.canonicalTransactionHash)
        assertEquals(listOf("created-output"), transaction.outputIds)
        assertTrue(transaction.pending)
    }

    @Test
    fun transactions_createdOutputFromOutgoing_doesNotReturnIncoming() = runBlocking {
        val destination = mwebDestination()
        val daemonClient = FakeDaemonClient(
            streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, destination, 1, 100, 10, 1_000, spent = false)),
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
    fun transactions_mwebToPublicWithoutCreatedMwebOutput_doesNotStayPending() = runBlocking {
        val bridge = FakePublicTransactionBridge()
        val engine = engineWith(
            daemonClient = FakeDaemonClient(
                status = MwebDaemonStatus(MwebSyncState(100, 100, 100), nativeVersion = "test"),
                streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, "address", 1, 100, 95, 1_000, spent = false)),
                dryRunRawTransaction = rawTransactionWithoutPublicOutputs(),
                createdOutputIds = emptyList(),
            ),
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
        assertFalse(transaction.pending)
    }

    @Test
    fun transactions_stalePendingLocalTransaction_prunesTransactionAndPendingRaw() = runBlocking {
        var now = 1_000_000L
        val destination = mwebDestination()
        val engine = engineWith(
            daemonClient = FakeDaemonClient(
                streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, destination, 1, 100, 10, 1_000, spent = false)),
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
    fun sendInfo_mwebInput_usesDryRunFeeAndMwebOutpointTemplate() {
        val addressCodec = MwebAddressCodec(LitecoinKit.NetworkType.MainNet)
        val destination = addressCodec.encode(ByteArray(33) { 1 }, ByteArray(33) { 2 })
        val daemonClient = FakeDaemonClient(
            streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, destination, 7, 100, 10, 1_000, spent = false)),
            dryRunRawTransaction = rawTransactionWithOutput(7),
        )
        val engine = engineWith(daemonClient)
        engine.start()

        val sendInfo = engine.sendInfo(MwebSendRequest.MwebToMweb(destination, 50, 1), publicOptions())
        val template = transactionSerializer.deserialize(
            BitcoinInputMarkable(daemonClient.createRequests.first().rawTransaction)
        )

        assertEquals(7L, sendInfo.mwebFee)
        assertEquals(43L, sendInfo.changeValue)
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
                    streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, "address", 1, 100, utxoHeight, 1_000, spent = false)),
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
                    streamUtxos = listOf(MwebUtxo(SELECTED_OUTPUT_ID, "address", 1, 100, utxoHeight, 1_000, spent = false)),
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
        val bridge = FakePublicTransactionBridge(
            publicUtxos = listOf(publicUtxo(value = 5_000)),
            signedRawTransaction = signedRaw,
        )
        val daemonClient = FakeDaemonClient()
        val engine = engineWith(daemonClient)
        engine.start()

        val result = engine.send(
            request = MwebSendRequest.PublicToMweb(mwebDestination(), 1_000, 1),
            publicOptions = publicOptions(),
            publicTransactionBridge = bridge,
        )

        assertEquals(1, bridge.signCalls.size)
        assertEquals(1, bridge.processCreatedCount)
        assertArrayEquals(signedRaw, daemonClient.broadcastRawTransactions.single())
        assertArrayEquals(signedRaw, result.rawTransaction)
        assertEquals("test-transaction", result.canonicalTransactionHash)
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
        localTransactionTtlMillis: Long = 24 * 60 * 60 * 1_000L,
        currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() },
    ): LitecoinMwebEngine {
        val walletId = "mweb-test-${System.nanoTime()}"
        walletIds.add(walletId)
        val engine = LitecoinMwebEngine(
            context = context,
            seed = ByteArray(32),
            walletId = walletId,
            dispatcherProvider = dispatcherProvider,
            daemonClientFactory = { _: MwebDaemonConfig -> daemonClient },
            spentPollIntervalMillis = spentPollIntervalMillis,
            localTransactionTtlMillis = localTransactionTtlMillis,
            currentTimeMillisProvider = currentTimeMillisProvider,
        )
        engines.add(engine)
        return engine
    }

    private class FakeDaemonClient(
        private val status: MwebDaemonStatus = MwebDaemonStatus(MwebSyncState(0, 0, 0), nativeVersion = "test"),
        private val startError: Throwable? = null,
        private val streamUtxos: List<MwebUtxo> = emptyList(),
        spentOutputIds: List<String> = emptyList(),
        private val dryRunRawTransaction: ByteArray? = null,
        private val createdOutputIds: List<String> = listOf("created-output"),
    ) : MwebDaemonClient {
        private val addressCodec = MwebAddressCodec(LitecoinKit.NetworkType.MainNet)
        var spentOutputIds: List<String> = spentOutputIds
        val utxoFromHeights = mutableListOf<Int>()
        val createRequests = mutableListOf<CreateRequest>()
        val broadcastRawTransactions = mutableListOf<ByteArray>()
        var closedUtxoStreams = 0
            private set
        var stopCount = 0
            private set
        private var streamed = false
        private var utxoHandler: ((MwebUtxo) -> Unit)? = null
        private var utxoErrorHandler: ((Throwable) -> Unit)? = null

        override fun start(statusTimeoutMillis: Long): MwebDaemonStatus {
            startError?.let { throw it }
            return status
        }

        override fun stop() {
            stopCount += 1
        }

        override fun status(statusTimeoutMillis: Long): MwebDaemonStatus = status

        override fun addresses(fromIndex: Int, toIndex: Int): List<String> {
            return (fromIndex..toIndex).map { index ->
                addressCodec.encode(
                    scanPublicKey = ByteArray(33) { offset -> (index + offset + 1).toByte() },
                    spendPublicKey = ByteArray(33) { offset -> (index + offset + 34).toByte() },
                )
            }
        }

        override fun utxos(fromHeight: Int, onUtxo: (MwebUtxo) -> Unit, onError: (Throwable) -> Unit): Closeable {
            utxoFromHeights.add(fromHeight)
            utxoHandler = onUtxo
            utxoErrorHandler = onError
            if (!streamed) {
                streamUtxos.forEach(onUtxo)
                streamed = true
            }
            return Closeable { closedUtxoStreams += 1 }
        }

        fun emitUtxo(utxo: MwebUtxo) {
            utxoHandler?.invoke(utxo)
        }

        fun emitUtxoError(error: Throwable) {
            utxoErrorHandler?.invoke(error)
        }

        override fun spent(outputIds: List<String>): List<String> = spentOutputIds.filter { it in outputIds }

        override fun create(rawTransaction: ByteArray, feeRate: Int, dryRun: Boolean): MwebCreateResult {
            createRequests.add(CreateRequest(rawTransaction, dryRun))
            return MwebCreateResult(
                rawTransaction = dryRunRawTransaction?.takeIf { dryRun } ?: rawTransaction,
                outputIds = createdOutputIds,
            )
        }

        override fun broadcast(rawTransaction: ByteArray): String {
            broadcastRawTransactions.add(rawTransaction.copyOf())
            return "test-transaction"
        }
    }

    private class FakePublicTransactionBridge(
        private val publicUtxos: List<UnspentOutput> = emptyList(),
        private val signedRawTransaction: ByteArray = byteArrayOf(1),
    ) : MwebPublicTransactionBridge {
        private val transactionSerializer = BaseTransactionSerializer()
        val signCalls = mutableListOf<SignCall>()
        val outputCalls = mutableListOf<String>()
        var spendableCalls = 0
            private set
        var processCreatedCount = 0
            private set

        override fun spendableUtxos(options: MwebPublicSendOptions): List<UnspentOutput> {
            spendableCalls += 1
            return publicUtxos
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
            return signedRawTransaction.copyOf()
        }

        override fun processCreated(transaction: FullTransaction): FullTransaction {
            processCreatedCount += 1
            return transaction
        }

        override suspend fun sign(rawTransaction: ByteArray, selectedUtxos: List<UnspentOutput>): FullTransaction {
            signCalls.add(SignCall(rawTransaction.copyOf(), selectedUtxos))
            return transactionSerializer.deserialize(BitcoinInputMarkable(rawTransaction))
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

    private fun mwebTransactionHeader(): Transaction {
        return Transaction(version = 2, lockTime = 0).apply {
            extraPayload = byteArrayOf(1)
        }
    }

    private fun mwebDestination(): String {
        return MwebAddressCodec(LitecoinKit.NetworkType.MainNet)
            .encode(ByteArray(33) { 1 }, ByteArray(33) { 2 })
    }

    private fun waitUntil(condition: () -> Boolean) = runBlocking {
        withTimeout(1_000) {
            while (!condition()) {
                delay(10)
            }
        }
    }

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
    }
}

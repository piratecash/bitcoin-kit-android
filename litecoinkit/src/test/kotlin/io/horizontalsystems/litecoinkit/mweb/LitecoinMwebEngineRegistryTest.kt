package io.horizontalsystems.litecoinkit.mweb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressCodec
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClient
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonConfig
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonStatus
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebCreateResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LitecoinMwebEngineRegistryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val walletIds = mutableListOf<String>()
    private val handles = mutableListOf<LitecoinMwebEngineHandle>()
    private val ioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val dispatcherProvider = CoroutineMwebDispatcherProvider(io = ioDispatcher, callback = ImmediateDispatcher)

    @After
    fun tearDown() {
        handles.forEach { handle -> handle.release() }
        walletIds.forEach { walletId ->
            LitecoinMwebEngineRegistry.clear(context, walletId, LitecoinKit.NetworkType.MainNet)
            LitecoinMwebEngineRegistry.clear(context, walletId, LitecoinKit.NetworkType.TestNet)
        }
        ioDispatcher.close()
    }

    @Test
    fun acquire_sameWalletAndNetwork_reusesEngineAndRefCountsStartStop() {
        val walletId = walletId()
        val daemonClient = FakeDaemonClient()
        val first = handle(walletId, daemonClient)
        val second = handle(walletId, FakeDaemonClient())

        assertSame(first.engine, second.engine)

        first.start()
        second.start()
        assertEquals(1, daemonClient.startCount)
        assertEquals(0, daemonClient.stopCount)

        first.stop()
        assertEquals(0, daemonClient.stopCount)

        second.stop()
        assertEquals(1, daemonClient.stopCount)
    }

    @Test
    fun acquire_fourOwners_reusesEngineAndRefCountsStartStop() {
        val walletId = walletId()
        val daemonClient = FakeDaemonClient()
        val owners = (0 until 4).map { handle(walletId, daemonClient) }

        owners.forEach { it.start() }
        assertEquals(1, daemonClient.startCount)

        owners.take(3).forEach { it.stop() }
        assertEquals(0, daemonClient.stopCount)

        owners.last().stop()
        assertEquals(1, daemonClient.stopCount)
    }

    @Test
    fun acquire_differentWalletIds_createsDifferentEngines() {
        val first = handle(walletId(), FakeDaemonClient())
        val second = handle(walletId(), FakeDaemonClient())

        assertNotSame(first.engine, second.engine)
    }

    @Test
    fun acquire_differentNetworks_createsDifferentEngines() {
        val walletId = walletId()
        val mainNet = handle(walletId, FakeDaemonClient(), LitecoinKit.NetworkType.MainNet)
        val testNet = handle(walletId, FakeDaemonClient(), LitecoinKit.NetworkType.TestNet)

        assertNotSame(mainNet.engine, testNet.engine)
    }

    @Test
    fun acquire_sameKeyWithDifferentPeerAddress_throws() {
        val walletId = walletId()
        handle(walletId, FakeDaemonClient(), config = config(FakeDaemonClient(), peerAddress = "node-a"))

        assertThrows(IllegalStateException::class.java) {
            handle(walletId, FakeDaemonClient(), config = config(FakeDaemonClient(), peerAddress = "node-b"))
        }
    }

    @Test
    fun acquire_sameKeyWithDifferentRestorePoint_throws() {
        val walletId = walletId()
        handle(walletId, FakeDaemonClient(), config = config(FakeDaemonClient(), restorePoint = MwebRestorePoint.Activation))

        assertThrows(IllegalStateException::class.java) {
            handle(walletId, FakeDaemonClient(), config = config(FakeDaemonClient(), restorePoint = MwebRestorePoint.BlockHeight(100)))
        }
    }

    @Test
    fun clear_withActiveHandle_throws() {
        val walletId = walletId()
        handle(walletId, FakeDaemonClient())

        assertThrows(IllegalStateException::class.java) {
            LitecoinMwebEngineRegistry.clear(context, walletId, LitecoinKit.NetworkType.MainNet)
        }
    }

    @Test
    fun engine_afterRelease_throws() {
        val handle = handle(walletId(), FakeDaemonClient())
        handle.release()

        assertThrows(IllegalStateException::class.java) {
            handle.engine
        }
    }

    @Test
    fun acquire_afterReleaseAndClear_createsNewEngine() {
        val walletId = walletId()
        val first = handle(walletId, FakeDaemonClient())
        val firstEngine = first.engine
        first.release()

        LitecoinMwebEngineRegistry.clear(context, walletId, LitecoinKit.NetworkType.MainNet)
        val second = handle(walletId, FakeDaemonClient())

        assertNotSame(firstEngine, second.engine)
    }

    @Test
    fun acquire_concurrentSameKey_reusesEngine() {
        val walletId = walletId()
        val daemonClient = FakeDaemonClient()
        val executor = Executors.newFixedThreadPool(4)
        val startLatch = CountDownLatch(1)
        val acquiredHandles = Collections.synchronizedList(mutableListOf<LitecoinMwebEngineHandle>())

        val futures = (0 until 4).map {
            executor.submit<LitecoinMwebEngineHandle> {
                startLatch.await()
                LitecoinMwebEngineRegistry.acquire(
                    context = context,
                    seed = ByteArray(32),
                    walletId = walletId,
                    networkType = LitecoinKit.NetworkType.MainNet,
                    config = config(daemonClient),
                )
            }
        }
        startLatch.countDown()
        futures.map { it.get() }.forEach { handle ->
            handles.add(handle)
            acquiredHandles.add(handle)
        }
        executor.shutdown()

        assertEquals(1, acquiredHandles.map { it.engine }.toSet().size)
    }

    @Test
    fun start_startFailure_rollsBackStartCountAndAllowsRetry() {
        val walletId = walletId()
        val daemonClient = FakeDaemonClient(startError = IllegalStateException("boom"))
        val first = handle(walletId, daemonClient)
        val second = handle(walletId, FakeDaemonClient())

        assertThrows(MwebError.DaemonCrashed::class.java) {
            first.start()
        }
        assertThrows(MwebError.DaemonCrashed::class.java) {
            second.start()
        }

        assertEquals(2, daemonClient.startCount)
    }

    private fun handle(
        walletId: String,
        daemonClient: FakeDaemonClient,
        networkType: LitecoinKit.NetworkType = LitecoinKit.NetworkType.MainNet,
        config: MwebConfig = config(daemonClient),
    ): LitecoinMwebEngineHandle {
        val handle = LitecoinMwebEngineRegistry.acquire(
            context = context,
            seed = ByteArray(32),
            walletId = walletId,
            networkType = networkType,
            config = config,
        )
        handles.add(handle)
        return handle
    }

    private fun config(
        daemonClient: FakeDaemonClient,
        restorePoint: MwebRestorePoint = MwebRestorePoint.Activation,
        peerAddress: String? = null,
    ): MwebConfig {
        return MwebConfig(
            dispatcherProvider = dispatcherProvider,
            restorePoint = restorePoint,
            peerAddress = peerAddress,
            daemonClientFactory = { _: MwebDaemonConfig -> daemonClient },
        )
    }

    private fun walletId(): String {
        return "mweb-registry-test-${System.nanoTime()}".also(walletIds::add)
    }

    private class FakeDaemonClient(
        private val startError: Throwable? = null,
    ) : MwebDaemonClient {
        private val addressCodec = MwebAddressCodec(LitecoinKit.NetworkType.MainNet)
        var startCount = 0
            private set
        var stopCount = 0
            private set

        override fun start(statusTimeoutMillis: Long): MwebDaemonStatus {
            startCount += 1
            startError?.let { throw it }
            return MwebDaemonStatus(MwebSyncState(0, 0, 0), nativeVersion = "test")
        }

        override fun stop() {
            stopCount += 1
        }

        override fun status(statusTimeoutMillis: Long): MwebDaemonStatus {
            return MwebDaemonStatus(MwebSyncState(0, 0, 0), nativeVersion = "test")
        }

        override fun addresses(fromIndex: Int, toIndex: Int): List<String> {
            return (fromIndex..toIndex).map { index ->
                addressCodec.encode(
                    scanPublicKey = ByteArray(33) { offset -> (index + offset + 1).toByte() },
                    spendPublicKey = ByteArray(33) { offset -> (index + offset + 34).toByte() },
                )
            }
        }

        override fun utxos(fromHeight: Int, onUtxo: (MwebUtxo) -> Unit, onError: (Throwable) -> Unit): Closeable {
            return Closeable {}
        }

        override fun spent(outputIds: List<String>): List<String> = emptyList()

        override fun create(rawTransaction: ByteArray, feeRate: Int, dryRun: Boolean): MwebCreateResult {
            return MwebCreateResult(rawTransaction, outputIds = emptyList())
        }

        override fun broadcast(rawTransaction: ByteArray): String = "test-transaction"
    }

    private object ImmediateDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }
    }
}

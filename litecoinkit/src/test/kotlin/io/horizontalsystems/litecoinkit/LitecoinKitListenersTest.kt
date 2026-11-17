package io.horizontalsystems.litecoinkit

import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.managers.ConnectionManager
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.litecoinkit.mweb.CoroutineMwebDispatcherProvider
import io.horizontalsystems.litecoinkit.mweb.LitecoinMwebEngine
import io.horizontalsystems.litecoinkit.mweb.LitecoinMwebEngineHandle
import io.horizontalsystems.litecoinkit.mweb.MwebBalance
import io.horizontalsystems.litecoinkit.mweb.MwebConfig
import io.horizontalsystems.litecoinkit.mweb.daemon.MissingMwebDaemonClientFactory
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowLinuxWithDirectoryOpen::class])
class LitecoinKitListenersTest {
    private val context = testAppContext()
    private val dataDir = context.testDataDir
    private val mwebDataDir = context.testMwebDataDir
    private val walletId = "litecoin-listeners-${System.nanoTime()}"
    private val ioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val kits = mutableListOf<Pair<LitecoinKit, BitcoinCore>>()

    @After
    fun tearDown() {
        kits.forEach { (kit, realCore) ->
            kit.coreForTest = realCore
            kit.dispose()
        }
        LitecoinKit.clear(dataDir, mwebDataDir, LitecoinKit.NetworkType.MainNet, walletId)
        ioDispatcher.close()
    }

    @Test
    fun addListener_twoListeners_bothReceiveEveryCallback() {
        val kit = litecoinKit(mweb = true)
        val first = RecordingListener().also(kit::addListener)
        val second = RecordingListener().also(kit::addListener)

        emitAll(kit)

        assertEquals(ALL_EVENTS, first.events)
        assertEquals(ALL_EVENTS, second.events)
    }

    @Test
    fun removeListener_removedListener_receivesNothingAfterwards() {
        val kit = litecoinKit(mweb = true)
        val kept = RecordingListener().also(kit::addListener)
        val removed = RecordingListener().also(kit::addListener)

        kit.removeListener(removed)
        emitAll(kit)

        assertEquals(ALL_EVENTS, kept.events)
        assertEquals(emptyList<String>(), removed.events)
    }

    @Test
    fun addListener_reAddedAfterRemoval_receivesEachCallbackOnce() {
        val kit = litecoinKit(mweb = true)
        val listener = RecordingListener().also(kit::addListener)

        kit.removeListener(listener)
        kit.addListener(listener)
        kit.addListener(listener)
        emitAll(kit)

        assertEquals(ALL_EVENTS, listener.events)
        assertEquals(1, kit.mwebEngineForTest().engineListeners().size)
    }

    @Test
    fun dispose_twoListeners_stopsDeliveryToAll() {
        val kit = litecoinKit(mweb = true)
        val first = RecordingListener().also(kit::addListener)
        val second = RecordingListener().also(kit::addListener)
        val coreListener = kit.coreForTest.listener
        val engine = kit.mwebEngineForTest()

        kit.dispose()
        kits.clear()
        coreListener?.onBalanceUpdate(BalanceInfo(1, 0, 0))
        engine.engineListeners().forEach { it.onMwebBalanceUpdate(MwebBalance(1, 0)) }

        assertEquals(emptyList<String>(), first.events)
        assertEquals(emptyList<String>(), second.events)
    }

    @Test
    fun addListener_afterDispose_receivesNothingAndRegistersNoMwebAdapter() {
        val kit = litecoinKit(mweb = true)
        val coreListener = requireNotNull(kit.coreForTest.listener)
        val engine = kit.mwebEngineForTest()
        kit.dispose()
        kits.clear()

        val late = RecordingListener().also(kit::addListener)
        coreListener.onBalanceUpdate(BalanceInfo(1, 0, 0))

        assertEquals(emptyList<String>(), late.events)
        assertEquals(0, engine.engineListeners().size)
    }

    @Test(timeout = 30_000)
    fun start_twoConcurrentCalls_startCoreOnce() {
        val kit = litecoinKit(mweb = false)
        val coreStarting = CountDownLatch(1)
        val releaseCore = CountDownLatch(1)
        val core = mock<BitcoinCore> {
            on { start() } doAnswer {
                coreStarting.countDown()
                releaseCore.await(10, TimeUnit.SECONDS)
                Unit
            }
        }
        kit.coreForTest = core

        val firstStart = thread { kit.start() }
        assertTrue(coreStarting.await(10, TimeUnit.SECONDS))
        val secondDone = CountDownLatch(1)
        val secondStart = thread {
            kit.start()
            secondDone.countDown()
        }
        // The first start still runs, so the second one must be held back rather than reach the core.
        assertFalse(secondDone.await(500, TimeUnit.MILLISECONDS))
        releaseCore.countDown()
        firstStart.join(10_000)
        secondStart.join(10_000)

        verify(core, times(1)).start()
    }

    @Test
    fun start_afterPauseNetwork_startsCoreAgain() {
        val kit = litecoinKit(mweb = false)
        val core = mock<BitcoinCore>()
        kit.coreForTest = core

        kit.start()
        kit.pauseNetwork()
        kit.start()

        verify(core, times(2)).start()
    }

    private fun emitAll(kit: LitecoinKit) {
        val coreListener = requireNotNull(kit.coreForTest.listener)
        coreListener.onBalanceUpdate(BalanceInfo(1, 0, 0))
        coreListener.onTransactionsUpdate(emptyList(), emptyList())
        kit.mwebEngineForTest().engineListeners().forEach { it.onMwebBalanceUpdate(MwebBalance(1, 0)) }
    }

    private fun litecoinKit(mweb: Boolean): LitecoinKit {
        val kit = LitecoinKit(
            dataDir = dataDir,
            mwebDataDir = mwebDataDir,
            connectionManager = ConnectionManager.getInstance(context),
            seed = ByteArray(32),
            walletId = walletId,
            purpose = Purpose.BIP84,
            mwebConfig = if (mweb) mwebConfig() else null,
        )
        kits += kit to kit.coreForTest
        return kit
    }

    private fun mwebConfig() = MwebConfig(
        dispatcherProvider = CoroutineMwebDispatcherProvider(io = ioDispatcher),
        daemonClientFactory = MissingMwebDaemonClientFactory,
    )

    private var LitecoinKit.coreForTest: BitcoinCore
        get() = bitcoinCoreField().get(this) as BitcoinCore
        set(value) = bitcoinCoreField().set(this, value)

    private fun bitcoinCoreField() =
        LitecoinKit::class.java.getDeclaredField("bitcoinCore").apply { isAccessible = true }

    private fun LitecoinKit.mwebEngineForTest(): LitecoinMwebEngine {
        val field = LitecoinKit::class.java.getDeclaredField("mwebEngineHandle")
        field.isAccessible = true
        return (field.get(this) as LitecoinMwebEngineHandle).engine
    }

    // The engine has no public way to emit a balance without a running daemon.
    @Suppress("UNCHECKED_CAST")
    private fun LitecoinMwebEngine.engineListeners(): Collection<LitecoinMwebEngine.Listener> {
        val field = LitecoinMwebEngine::class.java.getDeclaredField("listeners")
        field.isAccessible = true
        return field.get(this) as Collection<LitecoinMwebEngine.Listener>
    }

    private class RecordingListener : LitecoinKit.Listener {
        val events = CopyOnWriteArrayList<String>()

        override fun onBalanceUpdate(balance: BalanceInfo) {
            events += BALANCE
        }

        override fun onTransactionsUpdate(inserted: List<TransactionInfo>, updated: List<TransactionInfo>) {
            events += TRANSACTIONS
        }

        override fun onMwebBalanceUpdate(balance: MwebBalance) {
            events += MWEB_BALANCE
        }
    }

    private companion object {
        const val BALANCE = "balance"
        const val TRANSACTIONS = "transactions"
        const val MWEB_BALANCE = "mwebBalance"
        val ALL_EVENTS = listOf(BALANCE, TRANSACTIONS, MWEB_BALANCE)
    }
}

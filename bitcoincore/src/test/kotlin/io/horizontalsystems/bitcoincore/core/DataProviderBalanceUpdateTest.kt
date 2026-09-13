package io.horizontalsystems.bitcoincore.core

import io.horizontalsystems.bitcoincore.managers.UnspentOutputProvider
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.BlockInfo
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.bitcoincore.randomBytes
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.TimeUnit

class DataProviderBalanceUpdateTest {

    private val storage = mock<IStorage>()
    private val unspentOutputProvider = mock<UnspentOutputProvider>()
    private val transactionInfoConverter = mock<ITransactionInfoConverter>()
    private val scheduler = TestScheduler()
    private val listener = RecordingListener()

    private val emptyBalance = BalanceInfo(0, 0, 0)
    private val restoredBalance = BalanceInfo(4394, 0, 0)

    @Before
    fun setUp() {
        RxJavaPlugins.setComputationSchedulerHandler { scheduler }
        whenever(unspentOutputProvider.getBalance()).thenReturn(emptyBalance)
    }

    @After
    fun tearDown() {
        RxJavaPlugins.setComputationSchedulerHandler(null)
    }

    @Test
    fun onTransactionsUpdate_balanceChanged_notifiesListener() {
        val dataProvider = createDataProvider()
        whenever(unspentOutputProvider.getBalance()).thenReturn(restoredBalance)

        dataProvider.onTransactionsUpdate(listOf(mock<Transaction>()), emptyList(), null)
        settleDebounce()

        assertEquals(restoredBalance, dataProvider.balance)
        assertEquals(listOf(restoredBalance), listener.balances)
    }

    @Test
    fun onBlockInsert_newHigherBlock_recomputesBalance() {
        val dataProvider = createDataProvider()
        whenever(unspentOutputProvider.getBalance()).thenReturn(restoredBalance)

        dataProvider.onBlockInsert(block(height = 963319))
        settleDebounce()

        assertEquals(restoredBalance, dataProvider.balance)
    }

    @Test
    fun onTransactionsUpdate_afterClear_doesNotRecomputeBalance() {
        val dataProvider = createDataProvider()
        dataProvider.clear()
        whenever(unspentOutputProvider.getBalance()).thenReturn(restoredBalance)

        dataProvider.onTransactionsUpdate(listOf(mock<Transaction>()), emptyList(), null)
        settleDebounce()

        assertEquals(emptyBalance, dataProvider.balance)
        assertEquals(emptyList<BalanceInfo>(), listener.balances)
    }

    private fun createDataProvider() =
        DataProvider(storage, unspentOutputProvider, transactionInfoConverter, "test")
            .apply { listener = this@DataProviderBalanceUpdateTest.listener }

    private fun settleDebounce() = scheduler.advanceTimeBy(1, TimeUnit.SECONDS)

    private fun block(height: Int) = Block(
        BlockHeader(
            version = 0,
            previousBlockHeaderHash = randomBytes(),
            merkleRoot = randomBytes(),
            timestamp = 1_755_000_000,
            bits = 0,
            nonce = 0,
            hash = randomBytes()
        ),
        height
    )

    private class RecordingListener : DataProvider.Listener {
        val balances = mutableListOf<BalanceInfo>()

        override fun onTransactionsUpdate(inserted: List<TransactionInfo>, updated: List<TransactionInfo>) = Unit
        override fun onTransactionsDelete(hashes: List<String>) = Unit
        override fun onLastBlockInfoUpdate(blockInfo: BlockInfo) = Unit
        override fun onBalanceUpdate(balance: BalanceInfo) {
            balances.add(balance)
        }
    }
}

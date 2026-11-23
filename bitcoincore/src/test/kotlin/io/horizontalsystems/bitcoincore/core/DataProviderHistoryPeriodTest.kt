package io.horizontalsystems.bitcoincore.core

import io.horizontalsystems.bitcoincore.managers.UnspentOutputProvider
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.TransactionFilterType
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DataProviderHistoryPeriodTest {

    private val storage = mock<IStorage>()
    private val unspentOutputProvider = mock<UnspentOutputProvider>()
    private val transactionInfoConverter = mock<ITransactionInfoConverter>()
    private lateinit var dataProvider: DataProvider

    @Before
    fun setUp() {
        whenever(unspentOutputProvider.getBalance()).thenReturn(BalanceInfo(0, 0, 0))
        dataProvider = DataProvider(storage, unspentOutputProvider, transactionInfoConverter, "test")
    }

    @Test
    fun transactions_period_passesBoundsToStorage() {
        whenever(storage.getFullTransactionInfo(null, TransactionFilterType.Incoming, 20, 100L, 200L)).thenReturn(emptyList())

        dataProvider.transactions(null, TransactionFilterType.Incoming, 20, 100L, 200L).test().assertValue(emptyList())

        verify(storage).getFullTransactionInfo(null, TransactionFilterType.Incoming, 20, 100L, 200L)
    }

    @Test
    fun oldestTransactionTimestamp_stored_emitsValue() {
        whenever(storage.getOldestTransactionTimestamp(TransactionFilterType.Outgoing)).thenReturn(150L)

        dataProvider.oldestTransactionTimestamp(TransactionFilterType.Outgoing).test().assertResult(150L)
    }

    @Test
    fun oldestTransactionTimestamp_nothingStored_completesEmpty() {
        whenever(storage.getOldestTransactionTimestamp(null)).thenReturn(null)

        dataProvider.oldestTransactionTimestamp(null).test().assertResult()
    }
}

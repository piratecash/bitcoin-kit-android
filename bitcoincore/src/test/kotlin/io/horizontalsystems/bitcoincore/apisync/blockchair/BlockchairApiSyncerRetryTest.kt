package io.horizontalsystems.bitcoincore.apisync.blockchair

import io.horizontalsystems.bitcoincore.RxTestRule
import io.horizontalsystems.bitcoincore.apisync.model.BlockHeaderItem
import io.horizontalsystems.bitcoincore.blocks.Blockchain
import io.horizontalsystems.bitcoincore.core.IApiSyncerListener
import io.horizontalsystems.bitcoincore.core.IApiTransactionProvider
import io.horizontalsystems.bitcoincore.core.IPublicKeyManager
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.managers.ApiSyncStateManager
import io.horizontalsystems.bitcoincore.managers.IRestoreKeyConverter
import io.reactivex.schedulers.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BlockchairApiSyncerRetryTest {

    private val storage = mock<IStorage>()
    private val restoreKeyConverter = mock<IRestoreKeyConverter>()
    private val transactionProvider = mock<IApiTransactionProvider>()
    private val lastBlockProvider = mock<LastBlockProvider>()
    private val publicKeyManager = mock<IPublicKeyManager>()
    private val blockchain = mock<Blockchain>()
    private val apiSyncStateManager = mock<ApiSyncStateManager>()
    private val listener = mock<IApiSyncerListener>()
    private val retryScheduler = TestScheduler()

    private lateinit var syncer: BlockchairApiSyncer

    @Before
    fun setup() {
        RxTestRule.setup()

        whenever(storage.getPublicKeys()).thenReturn(listOf())
        whenever(storage.downloadedTransactionsBestBlockHeight()).thenReturn(STOP_HEIGHT)
        whenever(lastBlockProvider.lastBlockHeader())
            .thenReturn(BlockHeaderItem(byteArrayOf(1), 900_000, 0))

        syncer = BlockchairApiSyncer(
            storage,
            restoreKeyConverter,
            transactionProvider,
            lastBlockProvider,
            publicKeyManager,
            blockchain,
            apiSyncStateManager,
            retryScheduler
        )
        syncer.listener = listener
    }

    @Test
    fun sync_providerFailsThenSucceeds_doesNotReportFailure() {
        whenever(transactionProvider.transactions(any(), any()))
            .thenThrow(RuntimeException("stream was reset: REFUSED_STREAM"))
            .thenReturn(listOf())

        syncer.sync()
        advancePastRetry(0)

        verify(listener, never()).onSyncFailed(any())
        verify(listener).onSyncSuccess()
    }

    @Test
    fun sync_providerAlwaysFails_reportsFailureOnceAfterBudget() {
        whenever(transactionProvider.transactions(any(), any()))
            .thenThrow(RuntimeException("stream was reset: CANCEL"))

        syncer.sync()
        advancePastWholeBudget()

        verify(transactionProvider, times(1 + RETRIES)).transactions(any(), any())
        verify(listener, times(1)).onSyncFailed(any())
    }

    @Test
    fun sync_failureWhileRetriesRemain_keepsListenerSilent() {
        whenever(transactionProvider.transactions(any(), any()))
            .thenThrow(RuntimeException("stream was reset: CANCEL"))

        syncer.sync()

        verifyNoInteractions(listener)
    }

    @Test
    fun terminate_pendingRetry_neverRuns() {
        whenever(transactionProvider.transactions(any(), any()))
            .thenThrow(RuntimeException("stream was reset: CANCEL"))

        syncer.sync()
        syncer.terminate()
        advancePastWholeBudget()

        verify(transactionProvider, times(1)).transactions(any(), any())
        verifyNoInteractions(listener)
    }

    @Test
    fun sync_calledAgain_resetsTheBudget() {
        whenever(transactionProvider.transactions(any(), any()))
            .thenThrow(RuntimeException("stream was reset: CANCEL"))

        syncer.sync()
        advancePastWholeBudget()
        syncer.sync()
        advancePastWholeBudget()

        verify(transactionProvider, times(2 * (1 + RETRIES))).transactions(any(), any())
        verify(listener, times(2)).onSyncFailed(any())
    }

    @Test
    fun sync_retryAfterPartialProgress_keepsTheOriginalCutoff() {
        // The failed attempt discovered block hashes, so the storage cutoff rises before the retry.
        whenever(storage.downloadedTransactionsBestBlockHeight())
            .thenReturn(STOP_HEIGHT, STOP_HEIGHT + 5_000)
        whenever(transactionProvider.transactions(any(), any()))
            .thenThrow(RuntimeException("stream was reset: CANCEL"))
            .thenReturn(listOf())

        syncer.sync()
        advancePastRetry(0)

        val stopHeights = argumentCaptor<Int>()
        verify(transactionProvider, times(2)).transactions(any(), stopHeights.capture())
        assertEquals(listOf(STOP_HEIGHT, STOP_HEIGHT), stopHeights.allValues)
    }

    @Test
    fun sync_staleRunFinishesItsCutoffQuery_doesNotChangeTheNewRunCutoff() {
        // The stale run is replaced while its own cutoff query is still in flight, then returns a
        // height discovered after the replacement started.
        val queries = AtomicInteger()
        whenever(storage.downloadedTransactionsBestBlockHeight()).thenAnswer {
            if (queries.getAndIncrement() == 0) {
                syncer.sync()
                STOP_HEIGHT + 5_000
            } else {
                STOP_HEIGHT
            }
        }
        whenever(transactionProvider.transactions(any(), any()))
            .thenThrow(RuntimeException("stream was reset: CANCEL"))
            .thenReturn(listOf())

        syncer.sync()
        advancePastRetry(0)

        val stopHeights = argumentCaptor<Int>()
        verify(transactionProvider, times(2)).transactions(any(), stopHeights.capture())
        assertEquals(listOf(STOP_HEIGHT, STOP_HEIGHT), stopHeights.allValues)
    }

    private fun advancePastRetry(attempt: Int) {
        retryScheduler.advanceTimeBy(maxDelayMs(attempt), TimeUnit.MILLISECONDS)
    }

    private fun advancePastWholeBudget() {
        repeat(RETRIES) { advancePastRetry(it) }
    }

    // The scheduled delay carries a random jitter of up to half the base, so advance past its top.
    private fun maxDelayMs(attempt: Int) = BASE_DELAYS_MS[attempt] * 3 / 2

    private companion object {
        const val STOP_HEIGHT = 100_000
        val BASE_DELAYS_MS = longArrayOf(5_000, 20_000, 60_000)
        val RETRIES = BASE_DELAYS_MS.size
    }
}

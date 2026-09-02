package io.horizontalsystems.bitcoincore

import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import io.horizontalsystems.bitcoincore.core.DataProvider
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IPublicKeyManager
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.core.PluginManager
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.managers.RestoreKeyConverterChain
import io.horizontalsystems.bitcoincore.managers.SyncManager
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.bitcoincore.models.TransactionStatus
import io.horizontalsystems.bitcoincore.models.TransactionType
import io.horizontalsystems.bitcoincore.network.peer.PeerGroup
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.transactions.AddressExtractor
import io.horizontalsystems.bitcoincore.utils.AddressConverterChain
import io.horizontalsystems.bitcoincore.utils.PaymentAddressParser
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.reactivex.Single
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitcoinCoreNetworkPauseTest {

    private val storage = mock<IStorage>()
    private val dataProvider = mock<DataProvider>()
    private val addressExtractor = mock<AddressExtractor>()
    private val syncManager = mock<SyncManager>()
    private val peerGroup = mock<PeerGroup>()
    private val peerGroupListener = mock<PeerGroup.Listener>()

    @Test
    fun pauseNetwork_stopsSyncing_keepsDataProviderAlive() {
        val bitcoinCore = bitcoinCore()

        bitcoinCore.pauseNetwork()

        assertTrue(bitcoinCore.isNetworkPaused)
        verify(syncManager).stop()
        verify(addressExtractor).stop()
        verify(dataProvider, never()).clear()
    }

    @Test
    fun stop_keepsDataProviderAlive_soRestartStillUpdatesBalance() {
        val bitcoinCore = bitcoinCore()

        bitcoinCore.stop()
        bitcoinCore.start()

        verify(dataProvider, never()).clear()
    }

    @Test
    fun dispose_clearsDataProvider_unlikeStop() {
        val bitcoinCore = bitcoinCore()

        bitcoinCore.dispose()

        verify(dataProvider).clear()
    }

    @Test
    fun transactions_networkPaused_returnsLocalDataWithoutRequestingInputs() {
        val bitcoinCore = bitcoinCore()
        val local = listOf(transactionInfo())
        whenever(dataProvider.transactions(null, null, null)).thenReturn(Single.just(local))

        bitcoinCore.pauseNetwork()

        assertEquals(local, bitcoinCore.transactions().blockingGet())
        verify(addressExtractor, never()).requestInputsByHash(any())
    }

    @Test
    fun transactions_afterStart_requestsInputsAgain() {
        val bitcoinCore = bitcoinCore()
        whenever(dataProvider.transactions(null, null, null))
            .thenReturn(Single.just(listOf(transactionInfo())))

        bitcoinCore.pauseNetwork()
        bitcoinCore.start()
        bitcoinCore.transactions().blockingGet()

        assertFalse(bitcoinCore.isNetworkPaused)
        verify(addressExtractor).requestInputsByHash(any())
    }

    @Test
    fun pauseNetwork_repeatedCycles_resumeSyncingEveryTime() {
        val bitcoinCore = bitcoinCore()

        repeat(3) {
            bitcoinCore.pauseNetwork()
            assertTrue(bitcoinCore.isNetworkPaused)
            bitcoinCore.start()
            assertFalse(bitcoinCore.isNetworkPaused)
        }

        verify(syncManager, times(3)).stop()
        verify(syncManager, times(3)).start()
    }

    @Test
    fun pauseNetwork_sharedKit_keepsPeerGroupRegistrations() {
        val bitcoinCore = sharedBitcoinCore()
        bitcoinCore.addPeerGroupListener(peerGroupListener)

        bitcoinCore.pauseNetwork()

        verify(peerGroup, never()).removePeerGroupListener(any())
        verify(peerGroup, never()).removePeerTaskHandler(any())
        verify(peerGroup, never()).removeInventoryItemsHandler(any())
    }

    @Test
    fun dispose_afterPauseNetwork_stillUnregistersFromSharedGroup() {
        val bitcoinCore = sharedBitcoinCore()
        bitcoinCore.addPeerGroupListener(peerGroupListener)

        bitcoinCore.pauseNetwork()
        bitcoinCore.dispose()

        verify(peerGroup).removePeerGroupListener(peerGroupListener)
        verify(dataProvider).clear()
    }

    private fun sharedBitcoinCore() = bitcoinCore().apply {
        isShared = true
        peerGroup = this@BitcoinCoreNetworkPauseTest.peerGroup
        bloomFilterManager = mock<BloomFilterManager>()
    }

    private fun bitcoinCore() = BitcoinCore(
        storage = storage,
        dataProvider = dataProvider,
        addressExtractor = addressExtractor,
        publicKeyManager = mock<IPublicKeyManager>(),
        addressConverter = mock<AddressConverterChain>(),
        restoreKeyConverterChain = mock<RestoreKeyConverterChain>(),
        transactionCreator = null,
        transactionFeeCalculator = null,
        replacementTransactionBuilder = null,
        paymentAddressParser = mock<PaymentAddressParser>(),
        syncManager = syncManager,
        purpose = Purpose.BIP84,
        peerManager = mock<PeerManager>(),
        dustCalculator = null,
        pluginManager = mock<PluginManager>(),
        connectionManager = mock<IConnectionManager>(),
    )

    private fun transactionInfo() = TransactionInfo(
        uid = "uid",
        transactionHash = "ab".repeat(32),
        transactionIndex = 0,
        inputs = emptyList(),
        outputs = emptyList(),
        amount = 1_000L,
        type = TransactionType.Incoming,
        fee = null,
        blockHeight = 1,
        timestamp = 1L,
        status = TransactionStatus.RELAYED,
    )
}

package io.horizontalsystems.bitcoincore

import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import io.horizontalsystems.bitcoincore.core.DataProvider
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IPublicKeyManager
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.core.PluginManager
import io.horizontalsystems.bitcoincore.managers.RestoreKeyConverterChain
import io.horizontalsystems.bitcoincore.managers.SyncManager
import io.horizontalsystems.bitcoincore.models.RawTransactionBroadcastResult
import io.horizontalsystems.bitcoincore.models.RawTransactionBroadcastStatus
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.transactions.AddressExtractor
import io.horizontalsystems.bitcoincore.transactions.TransactionCreator
import io.horizontalsystems.bitcoincore.utils.AddressConverterChain
import io.horizontalsystems.bitcoincore.utils.PaymentAddressParser
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class BitcoinCoreBroadcastTest {

    @Test
    fun broadcastRawTransaction_delegatesToTransactionCreator() {
        val transactionCreator = mock<TransactionCreator>()
        val expected = RawTransactionBroadcastResult(
            transaction = fullTransaction(),
            status = RawTransactionBroadcastStatus.Submitted,
        )
        val bitcoinCore = bitcoinCore(transactionCreator)

        runBlocking {
            whenever(transactionCreator.broadcastRawTransaction(RAW_HEX)).thenReturn(expected)

            val result = bitcoinCore.broadcastRawTransaction(RAW_HEX)

            assertSame(expected, result)
            verify(transactionCreator).broadcastRawTransaction(RAW_HEX)
        }
    }

    @Test
    fun broadcastRawTransaction_readOnlyCore_throwsReadOnlyError() {
        val bitcoinCore = bitcoinCore(transactionCreator = null)

        assertThrows(BitcoinCore.CoreError.ReadOnlyCore::class.java) {
            runBlocking { bitcoinCore.broadcastRawTransaction(RAW_HEX) }
        }
    }

    private fun bitcoinCore(transactionCreator: TransactionCreator?): BitcoinCore {
        return BitcoinCore(
            storage = mock<IStorage>(),
            dataProvider = mock<DataProvider>(),
            addressExtractor = mock<AddressExtractor>(),
            publicKeyManager = mock<IPublicKeyManager>(),
            addressConverter = mock<AddressConverterChain>(),
            restoreKeyConverterChain = mock<RestoreKeyConverterChain>(),
            transactionCreator = transactionCreator,
            transactionFeeCalculator = null,
            replacementTransactionBuilder = null,
            paymentAddressParser = mock<PaymentAddressParser>(),
            syncManager = mock<SyncManager>(),
            purpose = Purpose.BIP84,
            peerManager = mock<PeerManager>(),
            dustCalculator = null,
            pluginManager = mock<PluginManager>(),
            connectionManager = mock<IConnectionManager>(),
        )
    }

    private fun fullTransaction(): FullTransaction {
        return FullTransaction(
            header = Transaction(),
            inputs = emptyList(),
            outputs = emptyList(),
        )
    }

    private companion object {
        const val RAW_HEX = "01020304"
    }
}

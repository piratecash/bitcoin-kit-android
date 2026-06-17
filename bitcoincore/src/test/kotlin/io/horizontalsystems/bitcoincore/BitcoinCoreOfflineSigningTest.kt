package io.horizontalsystems.bitcoincore

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.core.DataProvider
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IPluginData
import io.horizontalsystems.bitcoincore.core.IPublicKeyManager
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.core.PluginManager
import io.horizontalsystems.bitcoincore.managers.IUnspentOutputProvider
import io.horizontalsystems.bitcoincore.managers.RestoreKeyConverterChain
import io.horizontalsystems.bitcoincore.managers.SyncManager
import io.horizontalsystems.bitcoincore.managers.UnspentOutputSelectorChain
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionDataSortType
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UnspentOutputInfo
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoincore.transactions.AddressExtractor
import io.horizontalsystems.bitcoincore.transactions.TransactionCreator
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.bitcoincore.utils.AddressConverterChain
import io.horizontalsystems.bitcoincore.utils.PaymentAddressParser
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BitcoinCoreOfflineSigningTest {

    private val pluginData = emptyMap<Byte, IPluginData>()
    private val filters = UtxoFilters(scriptTypes = listOf(ScriptType.P2WPKH))

    @Test
    fun createSignedTransaction_selectedUtxos_resolvesSpendableOutputsOnce() {
        val spendableOutput = unspentOutput(hashByte = 1, outputIndex = 0)
        val missingOutput = unspentOutput(hashByte = 2, outputIndex = 1)
        val provider = RecordingUnspentOutputProvider(listOf(spendableOutput))
        val transactionCreator = mock<TransactionCreator>()
        val expectedTransaction = fullTransaction()
        val selectedOutputs = listOf(
            UnspentOutputInfo.fromUnspentOutput(spendableOutput),
            UnspentOutputInfo.fromUnspentOutput(missingOutput),
        )
        val bitcoinCore = bitcoinCore(transactionCreator, provider)

        runBlocking {
            whenever(
                transactionCreator.createSigned(
                    toAddress = TO_ADDRESS,
                    memo = MEMO,
                    value = VALUE,
                    feeRate = FEE_RATE,
                    senderPay = SENDER_PAY,
                    sortType = SORT_TYPE,
                    unspentOutputs = listOf(spendableOutput),
                    pluginData = pluginData,
                    rbfEnabled = RBF_ENABLED,
                    changeToFirstInput = CHANGE_TO_FIRST_INPUT,
                    filters = filters,
                )
            ).thenReturn(expectedTransaction)

            val transaction = bitcoinCore.createSignedTransaction(
                address = TO_ADDRESS,
                memo = MEMO,
                value = VALUE,
                senderPay = SENDER_PAY,
                feeRate = FEE_RATE,
                sortType = SORT_TYPE,
                unspentOutputs = selectedOutputs,
                pluginData = pluginData,
                rbfEnabled = RBF_ENABLED,
                changeToFirstInput = CHANGE_TO_FIRST_INPUT,
                filters = filters,
            )

            assertSame(expectedTransaction, transaction)
            verify(transactionCreator).createSigned(
                toAddress = TO_ADDRESS,
                memo = MEMO,
                value = VALUE,
                feeRate = FEE_RATE,
                senderPay = SENDER_PAY,
                sortType = SORT_TYPE,
                unspentOutputs = listOf(spendableOutput),
                pluginData = pluginData,
                rbfEnabled = RBF_ENABLED,
                changeToFirstInput = CHANGE_TO_FIRST_INPUT,
                filters = filters,
            )
        }
        assertEquals(listOf(filters), provider.requests)
    }

    @Test
    fun createSignedTransaction_nullUtxos_doesNotResolveSpendableOutputs() {
        val provider = RecordingUnspentOutputProvider(emptyList())
        val transactionCreator = mock<TransactionCreator>()
        val expectedTransaction = fullTransaction()
        val bitcoinCore = bitcoinCore(transactionCreator, provider)

        runBlocking {
            whenever(
                transactionCreator.createSigned(
                    toAddress = TO_ADDRESS,
                    memo = MEMO,
                    value = VALUE,
                    feeRate = FEE_RATE,
                    senderPay = SENDER_PAY,
                    sortType = SORT_TYPE,
                    unspentOutputs = null,
                    pluginData = pluginData,
                    rbfEnabled = RBF_ENABLED,
                    changeToFirstInput = CHANGE_TO_FIRST_INPUT,
                    filters = filters,
                )
            ).thenReturn(expectedTransaction)

            val transaction = bitcoinCore.createSignedTransaction(
                address = TO_ADDRESS,
                memo = MEMO,
                value = VALUE,
                senderPay = SENDER_PAY,
                feeRate = FEE_RATE,
                sortType = SORT_TYPE,
                unspentOutputs = null,
                pluginData = pluginData,
                rbfEnabled = RBF_ENABLED,
                changeToFirstInput = CHANGE_TO_FIRST_INPUT,
                filters = filters,
            )

            assertSame(expectedTransaction, transaction)
            verify(transactionCreator).createSigned(
                toAddress = TO_ADDRESS,
                memo = MEMO,
                value = VALUE,
                feeRate = FEE_RATE,
                senderPay = SENDER_PAY,
                sortType = SORT_TYPE,
                unspentOutputs = null,
                pluginData = pluginData,
                rbfEnabled = RBF_ENABLED,
                changeToFirstInput = CHANGE_TO_FIRST_INPUT,
                filters = filters,
            )
        }
        assertEquals(emptyList<UtxoFilters>(), provider.requests)
    }

    private fun bitcoinCore(
        transactionCreator: TransactionCreator,
        unspentOutputProvider: RecordingUnspentOutputProvider
    ): BitcoinCore {
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
        ).apply {
            unspentOutputSelector = UnspentOutputSelectorChain(unspentOutputProvider)
        }
    }

    private fun fullTransaction(): FullTransaction {
        return FullTransaction(
            header = Transaction(),
            inputs = emptyList(),
            outputs = emptyList(),
        )
    }

    private fun unspentOutput(hashByte: Int, outputIndex: Int): UnspentOutput {
        val hash = ByteArray(32) { hashByte.toByte() }
        val transaction = Transaction().apply {
            this.hash = hash
            timestamp = hashByte.toLong()
        }
        val publicKey = PublicKey().apply {
            path = "0/0/$outputIndex"
        }
        val output = TransactionOutput(
            value = VALUE,
            index = outputIndex,
            script = byteArrayOf(hashByte.toByte()),
            type = ScriptType.P2WPKH,
            address = "address-$outputIndex",
        ).apply {
            transactionHash = hash
        }
        return UnspentOutput(output, publicKey, transaction, block = null)
    }

    private class RecordingUnspentOutputProvider(
        private val spendableOutputs: List<UnspentOutput>
    ) : IUnspentOutputProvider {
        val requests = mutableListOf<UtxoFilters>()

        override fun getSpendableUtxo(filters: UtxoFilters): List<UnspentOutput> {
            requests += filters
            return spendableOutputs
        }
    }

    private companion object {
        const val TO_ADDRESS = "bc1qofflineaddress"
        const val MEMO = "offline memo"
        const val VALUE = 100_000L
        const val FEE_RATE = 5
        const val SENDER_PAY = true
        const val RBF_ENABLED = true
        const val CHANGE_TO_FIRST_INPUT = false
        val SORT_TYPE = TransactionDataSortType.Bip69
    }
}

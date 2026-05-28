package io.horizontalsystems.bitcoincore.managers

import io.horizontalsystems.bitcoincore.DustCalculator
import io.horizontalsystems.bitcoincore.Fixtures
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoincore.transactions.TransactionSizeCalculator
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Test

class UnspentOutputSelectorTest {
    private val sizeCalculator = TransactionSizeCalculator()
    private val dustCalculator = DustCalculator(1_000, sizeCalculator)
    private val unspentOutputProvider = FakeUnspentOutputProvider()
    private val selector = UnspentOutputSelector(sizeCalculator, dustCalculator, unspentOutputProvider)

    @Test
    fun select_providerExcludesFailedOutputs_selectsCleanOutput() {
        val failedOutput = createUnspentOutput(value = 20_000, failedToSpend = true)
        val cleanOutput = createUnspentOutput(value = 10_000)
        unspentOutputProvider.outputs = listOf(failedOutput, cleanOutput)

        val selectedInfo = selector.select(
            value = 5_000,
            memo = null,
            feeRate = 1,
            outputScriptType = ScriptType.P2WPKH,
            changeType = ScriptType.P2WPKH,
            senderPay = true,
            pluginDataOutputSize = 0,
            changeToFirstInput = false,
            filters = UtxoFilters(),
        )

        assertEquals(listOf(cleanOutput), selectedInfo.outputs)
    }

    @Test
    fun selectSingleNoChange_providerExcludesFailedOutputs_selectsCleanOutput() {
        val failedOutput = createUnspentOutput(value = 5_110, failedToSpend = true)
        val cleanOutput = createUnspentOutput(value = 5_110)
        unspentOutputProvider.outputs = listOf(failedOutput, cleanOutput)

        val selector = UnspentOutputSelectorSingleNoChange(
            sizeCalculator,
            dustCalculator,
            unspentOutputProvider,
        )
        val selectedInfo = selector.select(
            value = 5_000,
            memo = null,
            feeRate = 1,
            outputScriptType = ScriptType.P2WPKH,
            changeType = ScriptType.P2WPKH,
            senderPay = true,
            pluginDataOutputSize = 0,
            changeToFirstInput = false,
            filters = UtxoFilters(),
        )

        assertEquals(listOf(cleanOutput), selectedInfo.outputs)
    }

    private fun createUnspentOutput(value: Long, failedToSpend: Boolean = false): UnspentOutput {
        val transaction = Transaction(version = 2, lockTime = 0)
        val output = TransactionOutput(
            value = value,
            index = 0,
            script = byteArrayOf(),
            type = ScriptType.P2WPKH,
            lockingScriptPayload = null,
        ).apply {
            this.failedToSpend = failedToSpend
            transactionHash = transaction.hash
        }

        return UnspentOutput(output, Fixtures.publicKey, transaction, null)
    }

    private class FakeUnspentOutputProvider : IUnspentOutputProvider {
        var outputs: List<UnspentOutput> = emptyList()

        override fun getSpendableUtxo(filters: UtxoFilters): List<UnspentOutput> {
            return outputs.filterNot { it.output.failedToSpend }
        }
    }
}

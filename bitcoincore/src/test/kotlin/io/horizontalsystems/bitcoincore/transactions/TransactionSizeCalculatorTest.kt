package io.horizontalsystems.bitcoincore.transactions

import io.horizontalsystems.bitcoincore.core.TransactionDataSorterFactory
import io.horizontalsystems.bitcoincore.models.AddressType
import io.horizontalsystems.bitcoincore.models.LegacyAddress
import io.horizontalsystems.bitcoincore.models.TransactionDataSortType
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.OutputSerializer
import io.horizontalsystems.bitcoincore.transactions.builder.MutableTransaction
import io.horizontalsystems.bitcoincore.transactions.builder.OutputSetter
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType.P2PK
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType.P2PKH
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType.P2SH
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType.P2WPKH
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType.P2WPKHSH
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionSizeCalculatorTest {
    private val calculator = TransactionSizeCalculator()

    private fun outputs(scriptTypes: List<ScriptType>): List<TransactionOutput> {
        return scriptTypes.map { TransactionOutput().apply { this.scriptType = it } }
    }

    @Test
    fun testTransactionSize() {
       /* assertEquals(10, calculator.transactionSize(listOf(), listOf(), 0))
        assertEquals(192, calculator.transactionSize(outputs(listOf(P2PKH)), listOf(P2PKH), 0))
        assertEquals(306, calculator.transactionSize(outputs(listOf(P2PKH, P2PK)), listOf(P2PKH), 0))
        assertEquals(303, calculator.transactionSize(outputs(listOf(P2PKH, P2PK)), listOf(P2WPKH), 0))
        assertEquals(350, calculator.transactionSize(outputs(listOf(P2PKH, P2PK)), listOf(P2PKH, P2PK), 0))

        assertEquals(113, calculator.transactionSize(outputs(listOf(P2WPKH)), listOf(P2PKH), 0))
        assertEquals(136, calculator.transactionSize(outputs(listOf(P2WPKHSH)), listOf(P2PKH), 0))
        assertEquals(261, calculator.transactionSize(outputs(listOf(P2WPKH, P2PKH)), listOf(P2PKH), 0))*/
    }

    @Test
    fun testInputSize() {
        assertEquals(148, calculator.inputSize(P2PKH))
        assertEquals(114, calculator.inputSize(P2PK))
        assertEquals(41, calculator.inputSize(P2WPKH))
        assertEquals(64, calculator.inputSize(P2WPKHSH))
    }

    @Test
    fun testOutputSize() {
        assertEquals(34, calculator.outputSize(P2PKH))
        assertEquals(32, calculator.outputSize(P2SH))
        assertEquals(44, calculator.outputSize(P2PK))
        assertEquals(31, calculator.outputSize(P2WPKH))
        assertEquals(32, calculator.outputSize(P2WPKHSH))
    }

    @Test
    fun transactionSize_unknownOutput_usesActualLockingScriptSize() {
        val previousOutputs = outputs(listOf(P2WPKH))
        val mwebOutput = TransactionOutput(
            value = 1,
            index = 0,
            script = ByteArray(66),
            type = ScriptType.UNKNOWN,
        )

        assertEquals(154, calculator.transactionSize(previousOutputs, listOf(mwebOutput)))
    }

    @Test
    fun transactionSize_memo_addsSerializedSizeOfProducedMemoOutput() {
        val previousOutputs = outputs(listOf(P2WPKH))

        listOf(75, 76, 220).forEach { length ->
            val memo = "a".repeat(length)
            val estimate = calculator.transactionSize(previousOutputs, listOf(), memo) -
                calculator.transactionSize(previousOutputs, listOf())

            assertEquals("length $length", OutputSerializer.serialize(producedMemoOutput(memo)).size.toLong(), estimate)
        }
    }

    private fun producedMemoOutput(memo: String): TransactionOutput {
        val transaction = MutableTransaction().apply {
            recipientAddress = LegacyAddress("recipient", ByteArray(20), AddressType.PubKeyHash)
            this.memo = memo
        }
        OutputSetter(TransactionDataSorterFactory()).setOutputs(transaction, TransactionDataSortType.None)
        return transaction.outputs.last()
    }
}

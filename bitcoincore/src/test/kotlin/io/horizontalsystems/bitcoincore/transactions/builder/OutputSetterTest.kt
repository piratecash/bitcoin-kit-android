package io.horizontalsystems.bitcoincore.transactions.builder

import io.horizontalsystems.bitcoincore.core.TransactionDataSorterFactory
import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.models.AddressType
import io.horizontalsystems.bitcoincore.models.LegacyAddress
import io.horizontalsystems.bitcoincore.models.TransactionDataSortType
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputSetterTest {
    private val outputSetter = OutputSetter(TransactionDataSorterFactory())

    @Test
    fun setOutputs_memoUpTo75Bytes_usesDirectPush() {
        listOf(1, 75).forEach { length ->
            val data = ByteArray(length) { 'a'.code.toByte() }

            assertArrayEquals("length $length", byteArrayOf(0x6a, length.toByte()) + data, memoScript(String(data)))
        }
    }

    @Test
    fun setOutputs_memoFrom76Bytes_usesPushData1() {
        listOf(76, 80, 220).forEach { length ->
            val data = ByteArray(length) { 'a'.code.toByte() }

            assertArrayEquals("length $length", byteArrayOf(0x6a, 0x4c, length.toByte()) + data, memoScript(String(data)))
        }
    }

    @Test
    fun setOutputs_relayLimitMemos_scriptFitsRelayPolicySize() {
        assertEquals(83, memoScript("a".repeat(80)).size)
        assertEquals(223, memoScript("a".repeat(220)).size)
    }

    @Test
    fun setOutputs_75ByteMemo_matchesLegacyEncoding() {
        val legacyScript = ("6a4b" + "61".repeat(75)).hexToByteArray()

        assertArrayEquals(legacyScript, memoScript("a".repeat(75)))
    }

    @Test
    fun setOutputs_multiByteMemo_pushesUtf8Bytes() {
        listOf("П".repeat(40), "😀".repeat(20)).forEach { memo ->
            val data = memo.toByteArray(Charsets.UTF_8)

            assertArrayEquals(memo, byteArrayOf(0x6a, 0x4c, 80) + data, memoScript(memo))
        }
    }

    @Test
    fun setOutputs_pluginDataAndMemo_keepsPluginOutputUnchanged() {
        val pluginData = byteArrayOf(0x02, 0x07, 0x00, 0x14) + ByteArray(20) { 0x11 }

        val outputs = setOutputs("memo") { addPluginData(0x51, pluginData) }

        assertEquals(3, outputs.size)
        assertEquals(ScriptType.NULL_DATA, outputs[1].scriptType)
        assertArrayEquals(byteArrayOf(0x6a, 0x51) + pluginData, outputs[1].lockingScript)
        assertArrayEquals(byteArrayOf(0x6a, 0x04) + "memo".toByteArray(), outputs[2].lockingScript)
    }

    private fun memoScript(memo: String): ByteArray {
        val memoOutput = setOutputs(memo).last()
        assertEquals(ScriptType.NULL_DATA, memoOutput.scriptType)
        return memoOutput.lockingScript
    }

    private fun setOutputs(memo: String, configure: MutableTransaction.() -> Unit = {}): List<TransactionOutput> {
        val transaction = MutableTransaction().apply {
            recipientAddress = LegacyAddress("recipient", ByteArray(20), AddressType.PubKeyHash)
            recipientValue = 1_000
            this.memo = memo
            configure()
        }
        outputSetter.setOutputs(transaction, TransactionDataSortType.None)
        return transaction.outputs
    }
}

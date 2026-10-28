package io.horizontalsystems.bitcoincore.core

import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionMetadata
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.models.TransactionType
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransactionInfo
import io.horizontalsystems.bitcoincore.storage.InputWithPreviousOutput
import io.horizontalsystems.bitcoincore.transactions.scripts.OpCodes
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaseTransactionInfoConverterTest {
    private val converter = BaseTransactionInfoConverter(PluginManager())

    @Test
    fun transactionInfo_unknownPreviousOutput_hidesInputAddress() {
        val info = converter.transactionInfo(fullTransactionInfo(ScriptType.UNKNOWN))

        assertNull(info.inputs.single().address)
    }

    @Test
    fun transactionInfo_knownPreviousOutput_keepsInputAddress() {
        val info = converter.transactionInfo(fullTransactionInfo(ScriptType.P2WPKH))

        assertEquals(SENDER_ADDRESS, info.inputs.single().address)
    }

    @Test
    fun transactionInfo_missingPreviousOutput_keepsInputAddress() {
        val info = converter.transactionInfo(fullTransactionInfo(null))

        assertEquals(SENDER_ADDRESS, info.inputs.single().address)
    }

    @Test
    fun transactionInfo_blankInputAddress_returnsNullAddress() {
        val info = converter.transactionInfo(
            fullTransactionInfo(
                previousOutputScriptType = null,
                inputAddress = "   "
            )
        )

        assertNull(info.inputs.single().address)
    }

    @Test
    fun transactionInfo_missingPreviousOutputUnsupportedWitnessAddress_hidesInputAddress() {
        val info = converter.transactionInfo(
            fullTransactionInfo(
                previousOutputScriptType = null,
                inputAddress = UNSUPPORTED_WITNESS_ADDRESS
            )
        )

        assertNull(info.inputs.single().address)
    }

    @Test
    fun transactionInfo_missingPreviousOutputSupportedWitnessAddress_keepsInputAddress() {
        val info = converter.transactionInfo(
            fullTransactionInfo(
                previousOutputScriptType = null,
                inputAddress = SUPPORTED_WITNESS_ADDRESS
            )
        )

        assertEquals(SUPPORTED_WITNESS_ADDRESS, info.inputs.single().address)
    }

    @Test
    fun transactionInfo_missingPreviousOutputLegacyAddress_keepsInputAddress() {
        val info = converter.transactionInfo(
            fullTransactionInfo(
                previousOutputScriptType = null,
                inputAddress = LEGACY_ADDRESS
            )
        )

        assertEquals(LEGACY_ADDRESS, info.inputs.single().address)
    }

    @Test
    fun transactionInfo_missingPreviousOutputCashAddress_keepsInputAddress() {
        val info = converter.transactionInfo(
            fullTransactionInfo(
                previousOutputScriptType = null,
                inputAddress = CASH_ADDRESS
            )
        )

        assertEquals(CASH_ADDRESS, info.inputs.single().address)
    }

    @Test
    fun transactionInfo_pushedMemo_returnsMemo() {
        val memos = listOf(1, 75, 76, 80, 220).map { "a".repeat(it) } + "П".repeat(40) + "\uD83D\uDE00".repeat(20)

        memos.forEach { memo ->
            val script = OpCodes.nullData(memo.toByteArray(Charsets.UTF_8))

            assertEquals(memo, memoOf(script))
        }
    }

    @Test
    fun transactionInfo_pushData1Memo_returnsWholeMemo() {
        val script = ("6a4c4c" + "61".repeat(76)).hexToByteArray()

        assertEquals("a".repeat(76), memoOf(script))
    }

    @Test
    fun transactionInfo_hodlerNullData_returnsNullMemo() {
        val script = ("6a51" + "020700" + "14" + "11".repeat(20)).hexToByteArray()

        assertNull(memoOf(script))
    }

    @Test
    fun transactionInfo_truncatedPushData1_returnsNullMemo() {
        val script = ("6a4c50" + "61".repeat(10)).hexToByteArray()

        assertNull(memoOf(script))
    }

    private fun memoOf(script: ByteArray): String? {
        val output = TransactionOutput(
            value = 0,
            index = 0,
            script = script,
            type = ScriptType.NULL_DATA,
            lockingScriptPayload = script
        )

        return converter.transactionInfo(fullTransactionInfo(null, outputs = listOf(output))).outputs.single().memo
    }

    private fun fullTransactionInfo(
        previousOutputScriptType: ScriptType?,
        inputAddress: String = SENDER_ADDRESS,
        outputs: List<TransactionOutput> = emptyList()
    ): FullTransactionInfo {
        val header = Transaction().apply {
            hash = TRANSACTION_HASH
            timestamp = 100
            status = Transaction.Status.RELAYED
        }
        val input = TransactionInput(
            previousOutputTxHash = PREVIOUS_TRANSACTION_HASH,
            previousOutputIndex = 0,
            sequence = 0xffffffffL
        ).apply {
            transactionHash = TRANSACTION_HASH
            address = inputAddress
        }
        val previousOutput = previousOutputScriptType?.let { scriptType ->
            TransactionOutput(
                value = 100,
                index = 0,
                script = byteArrayOf(),
                type = scriptType,
                address = SENDER_ADDRESS
            )
        }
        val metadata = TransactionMetadata(TRANSACTION_HASH).apply {
            amount = 100
            type = TransactionType.Incoming
        }

        return FullTransactionInfo(
            block = null,
            header = header,
            inputs = listOf(InputWithPreviousOutput(input, previousOutput)),
            outputs = outputs,
            metadata = metadata,
            transactionSerializer = BaseTransactionSerializer()
        )
    }

    private companion object {
        val TRANSACTION_HASH = byteArrayOf(1)
        val PREVIOUS_TRANSACTION_HASH = byteArrayOf(2)
        const val SENDER_ADDRESS = "ltc1sender"
        const val UNSUPPORTED_WITNESS_ADDRESS = "ltc1gdv96whejky6q53rvxpzxmxpg0xwvr49nqvktpjr78k0wjklvffrsgxvxdl"
        const val SUPPORTED_WITNESS_ADDRESS = "ltc1q9z5mzd0k72k8f8g9cny70a4rvv7ne48x336jw5"
        const val LEGACY_ADDRESS = "LhuY7P7TWxmaUz86GVDsfXuyBEy5LWXrFD"
        const val CASH_ADDRESS = "bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a"
    }
}

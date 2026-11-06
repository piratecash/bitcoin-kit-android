package io.horizontalsystems.bitcoincore.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TransactionInfoTest {

    @Test
    fun serialize_inputWithSpentOutput_parsedInfoKeepsIt() {
        val parsed = TransactionInfo(transactionInfo(spentOutput = SPENT_OUTPUT).serialize())

        assertEquals(SPENT_OUTPUT, parsed.inputs.single().spentOutput)
    }

    @Test
    fun serialize_inputWithoutSpentOutput_omitsField() {
        assertFalse(SPENT_OUTPUT_KEY in transactionInfo(spentOutput = null).serialize())
    }

    @Test
    fun constructor_jsonWrittenBeforeSpentOutputExisted_parsesToNull() {
        val parsed = TransactionInfo(JSON_WITHOUT_SPENT_OUTPUT)

        val input = parsed.inputs.single()
        assertEquals(INPUT_ADDRESS, input.address)
        assertNull(input.spentOutput)
    }

    private fun transactionInfo(spentOutput: String?) = TransactionInfo(
        uid = "uid",
        transactionHash = "hash",
        transactionIndex = 0,
        inputs = listOf(TransactionInputInfo(mine = false, address = INPUT_ADDRESS, spentOutput = spentOutput)),
        outputs = emptyList(),
        amount = 100,
        type = TransactionType.Incoming,
        fee = null,
        blockHeight = null,
        timestamp = 1,
        status = TransactionStatus.INVALID
    )

    private companion object {
        const val SPENT_OUTPUT_KEY = "spentOutput"
        const val SPENT_OUTPUT = "0b0a:7"
        const val INPUT_ADDRESS = "sender"
        val JSON_WITHOUT_SPENT_OUTPUT = """
            {"uid":"uid","transactionHash":"hash","transactionIndex":0,
            "inputs":[{"mine":false,"address":"$INPUT_ADDRESS"}],"outputs":[],
            "amount":100,"type":1,"timestamp":1,"status":3}
        """.trimIndent()
    }
}

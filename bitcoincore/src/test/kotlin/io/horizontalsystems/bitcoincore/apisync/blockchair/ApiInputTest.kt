package io.horizontalsystems.bitcoincore.apisync.blockchair

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiInputTest {

    @Test
    fun walletRecipient_witnessUnknown_returnsNull() {
        val input = buildApiInput(type = "witness_unknown")

        assertNull(input.walletRecipient)
    }

    @Test
    fun walletRecipient_regularType_returnsRecipient() {
        val input = buildApiInput(type = "pubkeyhash")

        assertEquals(ADDRESS, input.walletRecipient)
    }

    @Test
    fun walletRecipient_missingType_returnsRecipient() {
        val input = buildApiInput(type = null)

        assertEquals(ADDRESS, input.walletRecipient)
    }

    @Test
    fun walletRecipient_emptyRecipient_returnsNull() {
        val input = buildApiInput(recipient = "")

        assertNull(input.walletRecipient)
    }

    @Test
    fun walletRecipient_blankRecipient_returnsNull() {
        val input = buildApiInput(recipient = "   ")

        assertNull(input.walletRecipient)
    }

    @Test
    fun walletRecipient_uppercaseWitnessUnknown_returnsNull() {
        val input = buildApiInput(type = "WITNESS_UNKNOWN")

        assertNull(input.walletRecipient)
    }

    @Test
    fun walletRecipient_decodedWitnessUnknown_returnsNull() {
        val input = Json.decodeFromString<ApiInput>(
            """
            {
              "recipient": "$ADDRESS",
              "transaction_hash": "previous-hash",
              "spending_transaction_hash": "spending-hash",
              "spending_sequence": 0,
              "script_hex": "5820",
              "value": 100,
              "type": "witness_unknown"
            }
            """.trimIndent()
        )

        assertNull(input.walletRecipient)
    }

    private fun buildApiInput(
        recipient: String = ADDRESS,
        type: String? = null
    ): ApiInput {
        return ApiInput(
            recipient = recipient,
            transactionHash = "previous-hash",
            spendingTransactionHash = "spending-hash",
            spendingSequence = 0,
            scriptHex = "5820",
            value = 100,
            type = type
        )
    }

    private companion object {
        const val ADDRESS = "ltc1sender"
    }
}

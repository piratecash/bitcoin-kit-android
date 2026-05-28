package io.horizontalsystems.bitcoincore.apisync.blockchair

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockchairTransactionResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decode_emptyDataArray_returnsEmptyData() {
        val response = json.decodeFromString<BlockchairTransactionResponse>(
            """
            {
              "data": [],
              "context": {
                "code": 200,
                "results": 0
              }
            }
            """.trimIndent()
        )

        assertTrue(response.data.isEmpty())
    }

    @Test
    fun decode_dataObject_returnsTransactionData() {
        val response = json.decodeFromString<BlockchairTransactionResponse>(
            """
            {
              "data": {
                "$TRANSACTION_HASH": {
                  "transaction": {
                    "hash": "$TRANSACTION_HASH",
                    "block_id": 123,
                    "date": "2026-05-24",
                    "time": "2026-05-24 09:23:35",
                    "fee": 1000
                  },
                  "inputs": [
                    {
                      "recipient": "ltc1sender",
                      "transaction_hash": "previous-hash",
                      "spending_transaction_hash": "$TRANSACTION_HASH",
                      "spending_sequence": 0,
                      "script_hex": "0014",
                      "value": 10000,
                      "type": "witness_v0_keyhash"
                    }
                  ],
                  "outputs": [
                    {
                      "recipient": "ltc1recipient",
                      "value": 9000
                    }
                  ]
                }
              },
              "context": {
                "code": 200,
                "results": 1
              }
            }
            """.trimIndent()
        )

        val transaction = response.data.getValue(TRANSACTION_HASH)

        assertEquals(1, response.data.size)
        assertEquals(TRANSACTION_HASH, transaction.transaction.hash)
        assertEquals("ltc1sender", transaction.inputs.first().recipient)
        assertEquals("ltc1recipient", transaction.outputs.first().recipient)
    }

    private companion object {
        const val TRANSACTION_HASH = "fe834b98ca3e59ed12a8d99bd64f1fcddfa9de723117bb13d4f2ebae5099f475"
    }
}

package io.horizontalsystems.bitcoincore.messages

import com.eclipsesource.json.JsonObject
import io.horizontalsystems.bitcoincore.extensions.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bip322Test {

    private val addressConverter = mainNetAddressConverter()

    private val txHashVectors = bip322Vectors("basic-test-vectors.json").get("tx_hashes").asArray().map { it.asObject() }

    private val simpleVectors = listOf("basic-test-vectors.json", "generated-test-vectors.json")
        .flatMap { bip322Vectors(it).get("simple").asArray().map { vector -> vector.asObject() } }
        .filter { it.getString("type", "") in listOf("p2wpkh", "p2tr") }

    @Test
    fun messageHash_basicVectors_matchesTaggedHash() {
        assertEquals(3, txHashVectors.size)
        txHashVectors.forEach { vector ->
            assertEquals(vector.getString("message_hash", ""), Bip322.messageHash(vector.getString("message", "")).toHexString())
        }
    }

    @Test
    fun toSpendAndToSign_basicVectors_matchTransactionIds() {
        txHashVectors.forEach { vector ->
            val address = addressConverter.convert(vector.getString("address", ""))
            val toSpend = Bip322.toSpend(vector.getString("message", ""), address.lockingScript, address.scriptType)
            val toSign = Bip322.toSign(toSpend)

            assertEquals(vector.getString("to_spend_tx_hash", ""), toSpend.header.hash.reversedArray().toHexString())
            assertEquals(vector.getString("to_sign_tx_hash", ""), toSign.header.hash.reversedArray().toHexString())
        }
    }

    @Test
    fun verifyBip322_simpleP2wpkhAndP2trVectors_signaturesVerifyAgainstKitSighash() {
        assertEquals(setOf("p2wpkh", "p2tr"), simpleVectors.map { it.getString("type", "") }.toSet())
        simpleVectors.forEach { vector ->
            val address = addressConverter.convert(vector.getString("address", ""))
            signatures(vector).forEach { signature ->
                assertTrue(signature, verifyBip322(address, vector.getString("message", ""), witnessOf(signature)))
            }
        }
    }

    @Test
    fun verifyBip322_vectorSignatureForOtherMessage_isRejected() {
        val vector = simpleVectors.first { it.getString("type", "") == "p2wpkh" }
        val address = addressConverter.convert(vector.getString("address", ""))

        signatures(vector).forEach { signature ->
            assertTrue(!verifyBip322(address, vector.getString("message", "") + "x", witnessOf(signature)))
        }
    }

    private fun signatures(vector: JsonObject) = vector.get("bip322_signatures").asArray().map { it.asString() }
}

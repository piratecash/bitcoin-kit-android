package io.horizontalsystems.bitcoincore.messages

import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.transactions.builder.EcdsaInputSigner
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.hdwalletkit.ECKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class StandardMessageTest {

    private val message = "Hello World"
    private val privateKey = privateKeyFromWif("L3VFeEujGtevx9w18HD1fhRbCH67Az2dpCymeRE1SoPK6XQtaN2k")
    private val publicKey = publicKeyOf(privateKey)
    private val compactSignature = runBlocking {
        EcdsaInputSigner(SingleKeyWallet(privateKey), BaseTransactionSerializer(), bitcoinNetwork())
            .signStandardMessage(publicKey, ScriptType.P2WPKH, message)
            .copyOfRange(1, 65)
    }

    @Test
    fun signature_keyOtherThanSigner_throwsSignatureMismatch() {
        val otherKey = publicKeyOf(privateKeyFromWif("KyrSGCFPhqZMjCe5fNTYddiLMp4tMj4gLKuJ26TsB2rvr1VJGPbt")).publicKey

        assertThrows(MessageSignError.SignatureMismatch::class.java) {
            StandardMessage.signature(ECKey.messageHash(message), compactSignature, otherKey, ScriptType.P2WPKH)
        }
    }

    @Test
    fun signature_scriptTypeWithoutBip137Header_throwsUnsupportedFormat() {
        listOf(ScriptType.P2TR, ScriptType.P2WSH, ScriptType.P2SH).forEach { scriptType ->
            assertThrows(MessageSignError.UnsupportedFormat::class.java) {
                StandardMessage.signature(ECKey.messageHash(message), compactSignature, publicKey.publicKey, scriptType)
            }
        }
    }
}

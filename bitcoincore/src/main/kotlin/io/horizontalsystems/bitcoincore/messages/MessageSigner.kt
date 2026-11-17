package io.horizontalsystems.bitcoincore.messages

import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.models.Address
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.serializers.InputSerializer
import io.horizontalsystems.bitcoincore.storage.InputToSign
import io.horizontalsystems.bitcoincore.transactions.builder.IInputSigner
import io.horizontalsystems.bitcoincore.transactions.builder.ISchnorrInputSigner
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.bitcoincore.utils.IAddressConverter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class MessageSigner(
    private val inputSigner: IInputSigner?,
    private val schnorrInputSigner: ISchnorrInputSigner?,
    private val storage: IStorage,
    private val addressConverter: IAddressConverter,
    private val scriptType: ScriptType,
) {

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun sign(address: String, message: String, format: MessageSignatureFormat): String {
        val ownKey = ownKey(address)
        return when (format) {
            MessageSignatureFormat.Standard -> Base64.encode(signStandard(ownKey.publicKey, message))
            MessageSignatureFormat.Bip322 -> BIP322_SIGNATURE_PREFIX + Base64.encode(signBip322(ownKey, message))
        }
    }

    private suspend fun signStandard(publicKey: PublicKey, message: String): ByteArray {
        val signer = inputSigner as? IStandardMessageSigner
        if (signer == null || !StandardMessage.isSupported(scriptType)) throw MessageSignError.UnsupportedFormat()
        return signer.signStandardMessage(publicKey, scriptType, message)
    }

    private suspend fun signBip322(ownKey: OwnKey, message: String): ByteArray {
        val toSpend = Bip322.toSpend(message, ownKey.address.lockingScript, scriptType)
        val toSign = Bip322.toSign(toSpend)
        val inputsToSign = listOf(InputToSign(toSign.inputs.single(), toSpend.outputs.single(), ownKey.publicKey))
        val witness = when (scriptType) {
            ScriptType.P2WPKH -> inputSigner?.sigScriptEcdsaData(toSign.header, inputsToSign, toSign.outputs, 0)
            ScriptType.P2TR -> schnorrInputSigner?.sigScriptSchnorrData(toSign.header, inputsToSign, toSign.outputs, 0)
            else -> null
        } ?: throw MessageSignError.UnsupportedFormat()
        return InputSerializer.serializeWitness(witness)
    }

    // The wallet's own script type wins over the decoded one: a `3…` address decodes as P2SH.
    private fun ownKey(address: String): OwnKey {
        val decoded = try {
            addressConverter.convert(address)
        } catch (_: Exception) {
            throw MessageSignError.NotOwnAddress()
        }
        val publicKey = publicKey(decoded) ?: throw MessageSignError.NotOwnAddress()
        val ownAddress = addressConverter.convert(publicKey, scriptType)
        if (ownAddress.stringValue != address) throw MessageSignError.NotOwnAddress()
        return OwnKey(publicKey, ownAddress)
    }

    private fun publicKey(decoded: Address): PublicKey? {
        val payload = decoded.lockingScriptPayload
        return when (decoded.scriptType) {
            ScriptType.P2PKH, ScriptType.P2WPKH -> storage.getPublicKeyByKeyOrKeyHash(payload)
            ScriptType.P2SH -> storage.getPublicKeyByScriptHashForP2PWKH(payload)
            ScriptType.P2TR -> storage.getPublicKeyByHashP2TR(payload)
            else -> null
        }
    }

    private class OwnKey(val publicKey: PublicKey, val address: Address)

    private companion object {
        // BIP-322 asks for "smp"; verifiers such as bip322-js reject it, so signatures go out unprefixed.
        const val BIP322_SIGNATURE_PREFIX = ""
    }
}

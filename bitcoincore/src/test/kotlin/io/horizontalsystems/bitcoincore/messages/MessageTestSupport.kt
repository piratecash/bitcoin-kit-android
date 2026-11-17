package io.horizontalsystems.bitcoincore.messages

import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonObject
import io.horizontalsystems.bitcoincore.core.IPrivateWallet
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.models.Address
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.serializers.InputSerializer
import io.horizontalsystems.bitcoincore.storage.InputToSign
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.bitcoincore.transactions.scripts.Sighash
import io.horizontalsystems.bitcoincore.utils.AddressConverterChain
import io.horizontalsystems.bitcoincore.utils.Base58AddressConverter
import io.horizontalsystems.bitcoincore.utils.SegwitAddressConverter
import io.horizontalsystems.hdwalletkit.Base58
import io.horizontalsystems.hdwalletkit.ECKey
import io.horizontalsystems.hdwalletkit.HDKey
import io.horizontalsystems.hdwalletkit.Utils
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Base64

internal const val BIP322_SIMPLE_PREFIX = "smp"

internal fun bip322Vectors(name: String): JsonObject {
    val stream = checkNotNull(MessageSigner::class.java.getResourceAsStream("/bip322/$name")) { "missing $name" }
    return stream.bufferedReader().use { Json.parse(it).asObject() }
}

internal fun mainNetAddressConverter() = AddressConverterChain().apply {
    prependConverter(SegwitAddressConverter("bc"))
    prependConverter(Base58AddressConverter(0, 5))
}

internal fun bitcoinNetwork(): Network = mock<Network>().also {
    whenever(it.sigHashValue).thenReturn(Sighash.ALL)
    whenever(it.sigHashForked).thenReturn(false)
}

internal fun privateKeyFromWif(wif: String): ByteArray = Base58.decode(wif).copyOfRange(1, 33)

internal fun publicKeyOf(privateKey: ByteArray): PublicKey {
    val compressed = ECKey.pubKeyFromPrivKey(privateKey)
    return PublicKey(0, 0, true, compressed, Utils.sha256Hash160(compressed))
}

internal class SingleKeyWallet(private val privateKey: ByteArray) : IPrivateWallet {
    override fun privateKey(account: Int, index: Int, external: Boolean): HDKey =
        HDKey.fromPrivate(privateKey, ByteArray(32), null, 0, 0, 0, false)
}

internal fun witnessOf(signature: String): List<ByteArray> {
    val bytes = Base64.getDecoder().decode(signature.removePrefix(BIP322_SIMPLE_PREFIX))
    return InputSerializer.deserializeWitness(BitcoinInputMarkable(bytes))
}

/** Verifies a BIP-322 simple witness for [address] with the kit's own BIP-143 / BIP-341 sighash. */
internal fun verifyBip322(address: Address, message: String, witness: List<ByteArray>): Boolean {
    val toSpend = Bip322.toSpend(message, address.lockingScript, address.scriptType)
    val toSign = Bip322.toSign(toSpend)
    val serializer = BaseTransactionSerializer()
    return when (address.scriptType) {
        ScriptType.P2WPKH -> {
            val (signature, publicKeyBytes) = witness
            val publicKey = PublicKey(0, 0, true, publicKeyBytes, Utils.sha256Hash160(publicKeyBytes))
            if (!publicKey.publicKeyHash.contentEquals(address.lockingScriptPayload)) return false
            val inputs = listOf(InputToSign(toSign.inputs.single(), toSpend.outputs.single(), publicKey))
            val hashType = signature.last()
            val content = serializer.serializeForSignature(toSign.header, inputs, toSign.outputs, 0, isWitness = true) +
                byteArrayOf(hashType, 0, 0, 0)
            hashType == Sighash.ALL && ECKey(publicKeyBytes).verifySignature(content, signature.copyOf(signature.size - 1))
        }
        ScriptType.P2TR -> {
            val inputs = listOf(InputToSign(toSign.inputs.single(), toSpend.outputs.single(), PublicKey()))
            val sighash = Utils.taggedHash("TapSighash", serializer.serializeForTaprootSignature(toSign.header, inputs, toSign.outputs, 0))
            val outputKey = ECKey(byteArrayOf(2) + address.lockingScriptPayload)
            witness.size == 1 && outputKey.verifySchnorr(sighash, witness.single())
        }
        else -> false
    }
}

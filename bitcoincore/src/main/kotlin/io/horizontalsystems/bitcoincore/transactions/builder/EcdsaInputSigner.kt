package io.horizontalsystems.bitcoincore.transactions.builder

import io.horizontalsystems.bitcoincore.core.IPrivateWallet
import io.horizontalsystems.bitcoincore.messages.IStandardMessageSigner
import io.horizontalsystems.bitcoincore.messages.StandardMessage
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.InputToSign
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.hdwalletkit.ECKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class EcdsaInputSigner(
    private val hdWallet: IPrivateWallet,
    private val transactionSerializer: BaseTransactionSerializer,
    private val network: Network
): IInputSigner, IStandardMessageSigner {

    override fun setTransactionSerializer(serializer: BaseTransactionSerializer) = Unit
    override fun setNetwork(network: Network) = Unit

    override suspend fun sigScriptEcdsaData(transaction: Transaction, inputsToSign: List<InputToSign>, outputs: List<TransactionOutput>, index: Int): List<ByteArray> {

        val input = inputsToSign[index]
        val prevOutput = input.previousOutput
        val publicKey = input.previousOutputPublicKey

        val privateKey = checkNotNull(hdWallet.privateKey(publicKey.account, publicKey.index, publicKey.external)) {
            throw Error.NoPrivateKey()
        }

        val txContent = transactionSerializer.serializeForSignature(
            transaction = transaction,
            inputsToSign = inputsToSign,
            outputs = outputs,
            inputIndex = index,
            isWitness = prevOutput.scriptType.isWitness || network.sigHashForked
        ) + byteArrayOf(network.sigHashValue, 0, 0, 0)
        val signature = privateKey.createSignature(txContent) + network.sigHashValue

        return when (prevOutput.scriptType) {
            ScriptType.P2PK -> listOf(signature)
            else -> listOf(signature, publicKey.publicKey)
        }
    }

    // signMessage is the one primitive that signs the message hash; keep its r||s, rebuild the header.
    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun signStandardMessage(publicKey: PublicKey, scriptType: ScriptType, message: String): ByteArray {
        val privateKey = hdWallet.privateKey(publicKey.account, publicKey.index, publicKey.external)
        val compactSignature = Base64.decode(privateKey.signMessage(message)).copyOfRange(1, COMPACT_SIGNATURE_END)
        return StandardMessage.signature(ECKey.messageHash(message), compactSignature, publicKey.publicKey, scriptType)
    }

    open class Error : Exception() {
        class NoPrivateKey : Error()
        class NoPreviousOutput : Error()
        class NoPreviousOutputAddress : Error()
    }

    private companion object {
        const val COMPACT_SIGNATURE_END = 65
    }
}

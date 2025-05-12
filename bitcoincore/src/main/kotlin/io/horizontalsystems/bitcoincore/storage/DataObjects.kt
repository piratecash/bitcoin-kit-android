package io.horizontalsystems.bitcoincore.storage

import androidx.room.Embedded
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionMetadata
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.utils.HashUtils

class BlockHeader(
    val version: Int,
    val previousBlockHeaderHash: ByteArray,
    val merkleRoot: ByteArray,
    val timestamp: Long,
    val bits: Long,
    val nonce: Long,
    val hash: ByteArray,
    val posBlockSig: ByteArray? = null,
    val posStakeHash: ByteArray? = null,
    val posStakeN: Int? = null,
)

open class FullTransaction(
    val header: Transaction,
    val inputs: List<TransactionInput>,
    val outputs: List<TransactionOutput>,
    transactionSerializer: BaseTransactionSerializer,
    forceHashUpdate: Boolean = true
) {

    lateinit var metadata: TransactionMetadata

    init {
        if (forceHashUpdate) {
            setHash(
                HashUtils.doubleSha256(
                    transactionSerializer.serialize(
                        this,
                        withWitness = false
                    )
                )
            )
        }
    }

    fun setHash(hash: ByteArray) {
        header.hash = hash

        metadata = TransactionMetadata(header.hash)

        inputs.forEach {
            it.transactionHash = header.hash
        }
        outputs.forEach {
            it.transactionHash = header.hash
        }
    }

}

class InputToSign(
    val input: TransactionInput,
    val previousOutput: TransactionOutput,
    val previousOutputPublicKey: PublicKey
)

class TransactionWithBlock(
    @Embedded val transaction: Transaction,
    @Embedded val block: Block?
)

class PublicKeyWithUsedState(
    @Embedded val publicKey: PublicKey,
    val usedCount: Int
) {

    val used: Boolean
        get() = usedCount > 0
}

class InputWithPreviousOutput(
    val input: TransactionInput,
    val previousOutput: TransactionOutput?
)

class UnspentOutput(
    @Embedded val output: TransactionOutput,
    @Embedded val publicKey: PublicKey,
    @Embedded val transaction: Transaction,
    @Embedded val block: Block?
)

class UnspentOutputInfo(
    val outputIndex: Int,
    val transactionHash: ByteArray,
    val timestamp: Long,
    val address: String?,
    val value: Long
) {
    companion object {
        fun fromUnspentOutput(unspentOutput: UnspentOutput): UnspentOutputInfo {
            return UnspentOutputInfo(
                unspentOutput.output.index,
                unspentOutput.output.transactionHash,
                unspentOutput.transaction.timestamp,
                unspentOutput.output.address,
                unspentOutput.output.value,
            )
        }
    }
}

class FullTransactionInfo(
    val block: Block?,
    val header: Transaction,
    val inputs: List<InputWithPreviousOutput>,
    val outputs: List<TransactionOutput>,
    val metadata: TransactionMetadata,
    val transactionSerializer: BaseTransactionSerializer,
) {

    val rawTransaction: String
        get() {
            return transactionSerializer.serialize(fullTransaction).toHexString()
        }

    val fullTransaction: FullTransaction
        get() = FullTransaction(header, inputs.map { it.input }, outputs, transactionSerializer)

}


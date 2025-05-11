package io.horizontalsystems.bitcoincore.serializers

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.InputToSign

object TransactionSerializerProvider {
    private var transactionSerializer: BaseTransactionSerializer = BaseTransactionSerializer()

    fun setTransactionSerializer(transactionSerializer: BaseTransactionSerializer) {
        this.transactionSerializer = transactionSerializer
    }

    fun deserialize(input: BitcoinInputMarkable) = transactionSerializer.deserialize(input)

    fun serialize(transaction: FullTransaction, withWitness: Boolean = true) =
        transactionSerializer.serialize(transaction, withWitness)

    fun serializeForSignature(
        transaction: Transaction,
        inputsToSign: List<InputToSign>,
        outputs: List<TransactionOutput>,
        inputIndex: Int,
        isWitness: Boolean  = false
    ) = transactionSerializer.serializeForSignature(
        transaction,
        inputsToSign,
        outputs,
        inputIndex,
        isWitness
    )

    fun serializeForTaprootSignature(
        transaction: Transaction,
        inputsToSign: List<InputToSign>,
        outputs: List<TransactionOutput>,
        inputIndex: Int
    ) = transactionSerializer.serializeForTaprootSignature(
        transaction,
        inputsToSign,
        outputs,
        inputIndex
    )
}

package io.horizontalsystems.piratecashkit.models

import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.utils.HashUtils

internal class SpecialTransaction(
    header: Transaction,
    inputs: List<TransactionInput>,
    outputs: List<TransactionOutput>,
    val extraPayload: ByteArray,
    val nTime: Long,
    val type: Int,
    transactionSerializer: BaseTransactionSerializer,
    forceHashUpdate: Boolean = true
) : FullTransaction(header, inputs, outputs, transactionSerializer, false) {

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
}

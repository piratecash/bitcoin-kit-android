package io.horizontalsystems.dashkit.models

import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction

class SpecialTransaction(
        val transaction: FullTransaction,
        extraPayload: ByteArray,
        private val transactionSerializer: BaseTransactionSerializer,
        forceHashUpdate: Boolean = true
): FullTransaction(
    header = transaction.header,
    inputs = transaction.inputs,
    outputs = transaction.outputs,
    transactionSerializer = transactionSerializer,
    forceHashUpdate = forceHashUpdate
)

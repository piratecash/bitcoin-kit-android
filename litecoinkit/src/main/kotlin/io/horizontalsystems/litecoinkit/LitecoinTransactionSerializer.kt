package io.horizontalsystems.litecoinkit

import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction

internal class LitecoinTransactionSerializer : BaseTransactionSerializer() {

    override fun serializeForTransactionHash(transaction: FullTransaction): ByteArray {
        if (transaction.header.extraPayload.isEmpty()) {
            return super.serializeForTransactionHash(transaction)
        }

        return serialize(transaction, withWitness = false, withExtraPayload = false)
    }
}

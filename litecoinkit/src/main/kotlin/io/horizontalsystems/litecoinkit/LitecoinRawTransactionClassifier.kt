package io.horizontalsystems.litecoinkit

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.litecoinkit.mweb.MwebFeeFormula

internal object LitecoinRawTransactionClassifier {
    private val serializer = LitecoinTransactionSerializer()

    fun isMweb(rawTransaction: ByteArray): Boolean {
        return try {
            val transaction = serializer.deserialize(BitcoinInputMarkable(rawTransaction))
            transaction.header.extraPayload.isNotEmpty() ||
                transaction.outputs.any(MwebFeeFormula::isMwebOutput)
        } catch (_: Exception) {
            false
        }
    }
}

package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.utils.HashUtils
import io.horizontalsystems.litecoinkit.LitecoinTransactionSerializer

internal object MwebRawTransactionHash {
    private val serializer = LitecoinTransactionSerializer()

    fun canonicalPublicHash(rawTransaction: ByteArray): String {
        val transaction = serializer.deserialize(BitcoinInputMarkable(rawTransaction))
        return HashUtils.doubleSha256(serializer.serializeForTransactionHash(transaction)).toReversedHex()
    }

    fun fullRawHash(rawTransaction: ByteArray): String {
        return HashUtils.doubleSha256(rawTransaction).toReversedHex()
    }
}

package io.horizontalsystems.dashkit.models

import io.horizontalsystems.bitcoincore.extensions.toReversedHex

class InstantTransactionState {
    private val instantTransactionHashes = mutableSetOf<String>()

    fun append(hash: ByteArray) {
        instantTransactionHashes.add(hash.toReversedHex())
    }

    fun clearAndAppend(hashes: List<ByteArray>) {
        instantTransactionHashes.clear()
        hashes.forEach {
            instantTransactionHashes.add(it.toReversedHex())
        }
    }

    fun isInstant(hash: ByteArray): Boolean {
        return instantTransactionHashes.contains(hash.toReversedHex())
    }
}

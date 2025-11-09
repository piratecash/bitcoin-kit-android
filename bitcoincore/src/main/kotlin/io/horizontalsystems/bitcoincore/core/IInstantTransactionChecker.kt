package io.horizontalsystems.bitcoincore.core

interface IInstantTransactionChecker {
    fun isTransactionInstant(txHash: ByteArray): Boolean
}

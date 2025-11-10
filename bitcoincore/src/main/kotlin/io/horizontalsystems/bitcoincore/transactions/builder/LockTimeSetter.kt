package io.horizontalsystems.bitcoincore.transactions.builder

import io.horizontalsystems.bitcoincore.core.IStorage

class LockTimeSetter(
    private val storage: IStorage,
    private val useLastBlockHeight: Boolean
) {

    fun setLockTime(transaction: MutableTransaction) {
        transaction.transaction.lockTime = if (useLastBlockHeight) {
            storage.lastBlock()?.height?.toLong() ?: 0
        } else {
            0
        }
    }

}

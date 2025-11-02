package io.horizontalsystems.dashkit.managers

import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.managers.IUnspentOutputProvider
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.dashkit.instantsend.InstantTransactionManager

class ConfirmedUnspentOutputProvider(
    private val storage: IStorage,
    private val confirmationsThreshold: Int,
    private val instantTransactionManager: InstantTransactionManager
) : IUnspentOutputProvider {
    override fun getSpendableUtxo(filters: UtxoFilters): List<UnspentOutput> {
        val lastBlockHeight = storage.lastBlock()?.height ?: 0

        return storage.getUnspentOutputs().filter {
            isOutputConfirmed(it, lastBlockHeight) && filters.filterUtxo(it, storage)
        }
    }

    private fun isOutputConfirmed(unspentOutput: UnspentOutput, lastBlockHeight: Int): Boolean {
        // Check if transaction is InstantSend locked - if so, it's immediately spendable
        if (instantTransactionManager.isTransactionInstant(unspentOutput.transaction.hash)) {
            return true
        }

        val block = unspentOutput.block ?: return false

        return block.height <= lastBlockHeight - confirmationsThreshold + 1
    }
}

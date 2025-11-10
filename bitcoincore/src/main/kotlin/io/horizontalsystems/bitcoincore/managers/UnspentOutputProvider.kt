package io.horizontalsystems.bitcoincore.managers

import io.horizontalsystems.bitcoincore.core.IInstantTransactionChecker
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.core.PluginManager
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UtxoFilters

class UnspentOutputProvider(
    private val storage: IStorage,
    private val confirmationsThreshold: Int = 6,
    val pluginManager: PluginManager,
    private val instantChecker: IInstantTransactionChecker? = null
) : IUnspentOutputProvider {

    override fun getSpendableUtxo(filters: UtxoFilters): List<UnspentOutput> {
        return allUtxo().filter {
            isSpendable(it) && filters.filterUtxo(it, storage)
        }
    }

    private fun isSpendable(utxo: UnspentOutput): Boolean {
        // Check InstantSend first - instant transactions are immediately spendable
        if (instantChecker?.isTransactionInstant(utxo.transaction.hash) == true) {
            return true
        }

        // Standard spendability checks
        if (!pluginManager.isSpendable(utxo)) {
            return false
        }

        if (utxo.transaction.status != Transaction.Status.RELAYED) {
            return false
        }

        return true
    }

    private fun getUnspendableTimeLockedUtxo() = allUtxo().filter {
        !pluginManager.isSpendable(it)
    }

    private fun getUnspendableNotRelayedUtxo() = allUtxo().filter {
        // Exclude instant transactions from unspendable
        val isInstant = instantChecker?.isTransactionInstant(it.transaction.hash) == true
        !isInstant && it.transaction.status != Transaction.Status.RELAYED
    }

    fun getBalance(): BalanceInfo {
        val spendable = getSpendableUtxo(UtxoFilters()).sumOf { it.output.value }
        val unspendableTimeLocked = getUnspendableTimeLockedUtxo().sumOf { it.output.value }
        val unspendableNotRelayed = getUnspendableNotRelayedUtxo().sumOf { it.output.value }

        return BalanceInfo(spendable, unspendableTimeLocked, unspendableNotRelayed)
    }

    // Only confirmed spendable outputs
    fun getConfirmedSpendableUtxo(filters: UtxoFilters): List<UnspentOutput> {
        val lastBlockHeight = storage.lastBlock()?.height ?: 0

        return getSpendableUtxo(filters).filter {
            val block = it.block ?: return@filter false
            return@filter block.height <= lastBlockHeight - confirmationsThreshold + 1
        }
    }

    private fun allUtxo(): List<UnspentOutput> {
        val unspentOutputs = storage.getUnspentOutputs()

        if (confirmationsThreshold == 0) return unspentOutputs

        val lastBlockHeight = storage.lastBlock()?.height ?: 0
        return unspentOutputs.filter {
            // InstantSend locked transactions are immediately available
            if (instantChecker?.isTransactionInstant(it.transaction.hash) == true) {
                return@filter true
            }

            // If a transaction is an outgoing transaction, then it can be used
            // even if it's not included in a block yet
            if (it.transaction.isOutgoing) {
                return@filter true
            }

            // If a transaction is an incoming transaction, then it can be used
            // only if it's included in a block and has enough number of confirmations
            val block = it.block ?: return@filter false
            if (block.height <= lastBlockHeight - confirmationsThreshold + 1) {
                return@filter true
            }

            false
        }
    }
}

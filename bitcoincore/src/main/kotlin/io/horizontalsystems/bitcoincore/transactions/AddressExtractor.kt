package io.horizontalsystems.bitcoincore.transactions

import io.horizontalsystems.bitcoincore.apisync.blockchair.Api
import io.horizontalsystems.bitcoincore.blocks.IBlockchainDataListener
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

class AddressExtractor(
    private val api: Api,
    private val storage: IStorage,
    private val dataListener: IBlockchainDataListener,
    private val logTag: String
) {
    private var coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun requestInputs(fullTransaction: FullTransaction) {
        ensureScope()

        coroutineScope.launch(Dispatchers.IO) {
            val hash = fullTransaction.header.hash

            if (hash.isEmpty()) return@launch

            try {
                val apiTransaction = api.getTransactions(listOf(hash.toReversedHex())).firstOrNull()
                if (apiTransaction == null) {
                    Timber.tag(logTag).d("No transaction found for hash: ${hash.toReversedHex()}")
                    return@launch
                }
                if(apiTransaction.inputs.firstOrNull()?.spendingTransactionHash.isNullOrEmpty() &&
                    apiTransaction.inputs.firstOrNull()?.spendingSequence == 0L &&
                    apiTransaction.inputs.size == fullTransaction.inputs.size) {
                    // For situation when API doesn't return spendingTransactionHash and spendingSequence
                    fullTransaction.inputs.forEachIndexed { index, input ->
                        apiTransaction.inputs.getOrNull(index)?.let {
                            input.address = it.recipient
                        }
                    }
                } else {
                    fullTransaction.inputs.forEach { input ->
                        apiTransaction.inputs.find {
                            it.spendingSequence == input.sequence &&
                                    it.spendingTransactionHash == input.transactionHash.toReversedHex()
                        }?.let {
                            input.address = it.recipient
//                        input.lockingScriptPayload = it.scriptHex.hexToByteArray()
                        }
                    }
                }

                storage.updateTransaction(fullTransaction)
                dataListener.onTransactionsUpdate(
                    inserted = emptyList(),
                    updated = listOf(fullTransaction.header),
                    block = null
                )
            } catch (e: Exception) {
                Timber.tag(logTag).d(
                    e,
                    "Failed to fetch transaction for input addresses, hash: ${hash.toReversedHex()}"
                )
            }
        }
    }

    private fun ensureScope() {
        if (coroutineScope.coroutineContext[Job]?.isActive != true) {
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }

    fun stop() {
        coroutineScope.cancel()
    }
}
package io.horizontalsystems.bitcoincore.transactions

import io.horizontalsystems.bitcoincore.apisync.blockchair.Api
import io.horizontalsystems.bitcoincore.apisync.blockchair.FullApiTransaction
import io.horizontalsystems.bitcoincore.blocks.IBlockchainDataListener
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import kotlinx.coroutines.CoroutineExceptionHandler
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
    private var coroutineScope = createCoroutineScope()

    private fun createCoroutineScope(): CoroutineScope {
        return CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, ex ->
                Timber.tag(logTag).d(ex)
            }
        )
    }

    fun requestInputs(fullTransaction: FullTransaction) {
        requestInputs(listOf(fullTransaction))
    }

    fun requestInputsByHash(hashes: List<ByteArray>) {
        ensureScope()

        coroutineScope.launch(Dispatchers.IO) {
            val transactions = hashes.mapNotNull { hash ->
                storage.getFullTransaction(hash)?.let { hash.toReversedHex() to it }
            }.toMap()

            requestInputsInternal(transactions.values.toList())
        }
    }

    fun requestInputs(fullTransactions: List<FullTransaction>) {
        ensureScope()

        coroutineScope.launch(Dispatchers.IO) {
            requestInputsInternal(fullTransactions)
        }
    }

    private suspend fun requestInputsInternal(fullTransactions: List<FullTransaction>) {
        // Filter transactions that need address extraction (have inputs without addresses)
        val transactionsToProcess = fullTransactions.filter { transaction ->
            transaction.hasEmptyInputAddresses() && transaction.header.isMine
                    && !transaction.header.isOutgoing
        }

        if (transactionsToProcess.isEmpty()) return

        try {
            val hashesToTransactions = transactionsToProcess.associateBy {
                it.header.hash.toReversedHex()
            }
            val apiTransactions = api.getTransactions(hashesToTransactions.keys.toList())
            val updated = mutableListOf<FullTransaction>()

            apiTransactions.forEach { apiTx ->
                val fullTransaction = hashesToTransactions[apiTx.transaction.hash] ?: return@forEach
                updateInputsFromApi(fullTransaction, apiTx)
                storage.updateTransaction(fullTransaction)
                updated += fullTransaction
            }

            if (updated.isNotEmpty()) {
                dataListener.onTransactionsUpdate(
                    inserted = emptyList(),
                    updated = updated.map { it.header },
                    block = null
                )
            }
        } catch (e: Exception) {
            Timber.tag(logTag).d(e, "Failed to fetch batched transactions for inputs")
        }
    }

    private fun updateInputsFromApi(fullTransaction: FullTransaction, apiTx: FullApiTransaction) {
        if (apiTx.inputs.firstOrNull()?.spendingTransactionHash.isNullOrEmpty() &&
            apiTx.inputs.firstOrNull()?.spendingSequence == 0L &&
            apiTx.inputs.size == fullTransaction.inputs.size
        ) {
            // For case when API doesn't return spendingTransactionHash and spendingSequence
            fullTransaction.inputs.forEachIndexed { index, input ->
                apiTx.inputs.getOrNull(index)?.let {
                    input.address = it.recipient
                }
            }
        } else {
            fullTransaction.inputs.forEach { input ->
                apiTx.inputs.find {
                    it.spendingSequence == input.sequence &&
                            it.spendingTransactionHash == input.transactionHash.toReversedHex()
                }?.let {
                    input.address = it.recipient
                }
            }
        }
    }

    private fun ensureScope() {
        if (coroutineScope.coroutineContext[Job]?.isActive != true) {
            coroutineScope = createCoroutineScope()
        }
    }

    fun stop() {
        coroutineScope.cancel()
    }
}
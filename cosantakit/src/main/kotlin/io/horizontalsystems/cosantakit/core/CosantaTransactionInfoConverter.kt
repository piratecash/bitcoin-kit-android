package io.horizontalsystems.cosantakit.core

import io.horizontalsystems.bitcoincore.core.BaseTransactionInfoConverter
import io.horizontalsystems.bitcoincore.core.ITransactionInfoConverter
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.models.InvalidTransaction
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionMetadata
import io.horizontalsystems.bitcoincore.models.TransactionStatus
import io.horizontalsystems.bitcoincore.storage.FullTransactionInfo
import io.horizontalsystems.cosantakit.instantsend.InstantTransactionManager
import io.horizontalsystems.cosantakit.models.CosantaTransactionInfo

class CosantaTransactionInfoConverter(private val instantTransactionManager: InstantTransactionManager) : ITransactionInfoConverter {
    override lateinit var baseConverter: BaseTransactionInfoConverter

    override fun transactionInfo(fullTransactionInfo: FullTransactionInfo): CosantaTransactionInfo {
        val transaction = fullTransactionInfo.header

        if (transaction.status == Transaction.Status.INVALID) {
            (transaction as? InvalidTransaction)?.let {
                return getInvalidTransactionInfo(it, fullTransactionInfo.metadata)
            }
        }

        val txInfo = baseConverter.transactionInfo(fullTransactionInfo)

        return CosantaTransactionInfo(
                txInfo.uid,
                txInfo.transactionHash,
                txInfo.transactionIndex,
                txInfo.inputs,
                txInfo.outputs,
                txInfo.amount,
                txInfo.type,
                txInfo.fee,
                txInfo.blockHeight,
                txInfo.timestamp,
                txInfo.status,
                txInfo.conflictingTxHash,
                instantTransactionManager.isTransactionInstant(fullTransactionInfo.header.hash)
        )
    }

    private fun getInvalidTransactionInfo(
        transaction: InvalidTransaction,
        metadata: TransactionMetadata
    ): CosantaTransactionInfo {
        return try {
            CosantaTransactionInfo(transaction.serializedTxInfo)
        } catch (ex: Exception) {
            CosantaTransactionInfo(
                uid = transaction.uid,
                transactionHash = transaction.hash.toHexString(),
                transactionIndex = transaction.order,
                inputs = listOf(),
                outputs = listOf(),
                amount = metadata.amount,
                type = metadata.type,
                fee = null,
                blockHeight = null,
                timestamp = transaction.timestamp,
                status = TransactionStatus.INVALID,
                conflictingTxHash = null,
                instantTx = false
            )
        }
    }

}
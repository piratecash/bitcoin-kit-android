package io.horizontalsystems.bitcoincore.transactions

import io.horizontalsystems.bitcoincore.core.IPluginData
import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.models.TransactionDataSortType
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.InputToSign
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoincore.transactions.builder.MutableTransaction
import io.horizontalsystems.bitcoincore.transactions.builder.TransactionBuilder
import io.horizontalsystems.bitcoincore.transactions.builder.SignedTransactionData
import io.horizontalsystems.bitcoincore.transactions.builder.TransactionSigner

class TransactionCreator(
    private val builder: TransactionBuilder,
    private val processor: PendingTransactionProcessor,
    private val transactionSender: TransactionSender,
    private val transactionSigner: TransactionSigner,
    private val bloomFilterManager: BloomFilterManager,
    private val transactionSerializer: BaseTransactionSerializer,
) {

    @Throws
    suspend fun create(
        toAddress: String,
        memo: String?,
        value: Long,
        feeRate: Int,
        senderPay: Boolean,
        sortType: TransactionDataSortType,
        unspentOutputs: List<UnspentOutput>?,
        pluginData: Map<Byte, IPluginData>,
        rbfEnabled: Boolean,
        changeToFirstInput: Boolean,
        filters: UtxoFilters
    ): FullTransaction {
        val mutableTransaction = builder.buildTransaction(
            toAddress = toAddress,
            memo = memo,
            value = value,
            feeRate = feeRate,
            senderPay = senderPay,
            sortType = sortType,
            unspentOutputs = unspentOutputs,
            pluginData = pluginData,
            rbfEnabled = rbfEnabled,
            changeToFirstInput = changeToFirstInput,
            filters = filters,
        )

        return create(mutableTransaction)
    }

    @Throws
    suspend fun create(
        unspentOutput: UnspentOutput,
        toAddress: String,
        memo: String?,
        feeRate: Int,
        sortType: TransactionDataSortType,
        rbfEnabled: Boolean
    ): FullTransaction {
        val mutableTransaction = builder.buildTransaction(unspentOutput, toAddress, memo, feeRate, sortType, rbfEnabled)

        return create(mutableTransaction)
    }

    suspend fun create(mutableTransaction: MutableTransaction): FullTransaction {
        val fullTransaction = signAndBuild(mutableTransaction)
        processAndSend(fullTransaction)
        return fullTransaction
    }

    suspend fun signRawTransaction(
        rawTransaction: ByteArray,
        unspentOutputs: List<UnspentOutput>,
    ): FullTransaction {
        val fullTransaction = transactionSerializer.deserialize(BitcoinInputMarkable(rawTransaction))
        val mutableTransaction = MutableTransaction()
        mutableTransaction.transaction.apply {
            version = fullTransaction.header.version
            lockTime = fullTransaction.header.lockTime
            timestamp = fullTransaction.header.timestamp
            isMine = true
            isOutgoing = true
            status = Transaction.Status.NEW
            segwit = fullTransaction.header.segwit
            extraPayload = fullTransaction.header.extraPayload
        }
        mutableTransaction.outputs = fullTransaction.outputs
        fullTransaction.inputs.forEach { input ->
            val unspentOutput = unspentOutputs.firstOrNull {
                it.transaction.hash.contentEquals(input.previousOutputTxHash) &&
                    it.output.index.toLong() == input.previousOutputIndex
            } ?: throw TransactionCreationException("No previous output for raw transaction input")
            mutableTransaction.addInput(InputToSign(input, unspentOutput.output, unspentOutput.publicKey))
        }
        return signAndBuild(mutableTransaction, fullTransaction.inputs)
    }

    fun serialize(transaction: FullTransaction, withWitness: Boolean = true): ByteArray {
        return transactionSerializer.serialize(transaction, withWitness)
    }

    fun processCreated(transaction: FullTransaction): FullTransaction {
        return processAndSend(transaction)
    }

    fun processCreatedLocally(transaction: FullTransaction): FullTransaction {
        return processCreatedInStorage(transaction)
    }

    private suspend fun signAndBuild(
        mutableTransaction: MutableTransaction,
        inputs: List<TransactionInput>? = null,
    ): FullTransaction {
        val signedData = transactionSigner.sign(mutableTransaction)

        val fullTransaction = if (signedData != null) {
            buildFromSignedData(signedData, mutableTransaction)
        } else {
            if (inputs == null) {
                mutableTransaction.build(transactionSerializer)
            } else {
                FullTransaction(
                    header = mutableTransaction.transaction,
                    inputs = inputs,
                    outputs = mutableTransaction.outputs,
                    transactionSerializer = transactionSerializer,
                )
            }
        }
        return fullTransaction
    }

    private fun buildFromSignedData(
        signedData: SignedTransactionData,
        mutableTransaction: MutableTransaction
    ): FullTransaction {
        val rawBytes = signedData.serializedTx.hexToByteArray()
        val deserialized = transactionSerializer.deserialize(
            BitcoinInputMarkable(rawBytes)
        )
        deserialized.header.apply {
            status = Transaction.Status.NEW
            isMine = true
            isOutgoing = mutableTransaction.transaction.isOutgoing
        }
        return deserialized
    }

    private fun processAndSend(transaction: FullTransaction): FullTransaction {
        // Always save the transaction first - don't block on peer availability
        processCreatedInStorage(transaction)

        // Attempt to broadcast - if no peers available, transaction will be queued
        // and automatically retried by TransactionSendTimer / SendTransactionsOnPeersSynced
        try {
            transactionSender.sendPendingTransactions()
        } catch (e: Exception) {
            // ignore any exception since the tx is inserted to the db
        }

        return transaction
    }

    private fun processCreatedInStorage(transaction: FullTransaction): FullTransaction {
        try {
            processor.processCreated(transaction)
        } catch (ex: BloomFilterManager.BloomFilterExpired) {
            bloomFilterManager.regenerateBloomFilter()
        }

        return transaction
    }

    open class TransactionCreationException(msg: String) : Exception(msg)
    class TransactionAlreadyExists(msg: String) : TransactionCreationException(msg)

}

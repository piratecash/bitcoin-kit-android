package io.horizontalsystems.bitcoincore.transactions

import io.horizontalsystems.bitcoincore.apisync.blockchair.Api
import io.horizontalsystems.bitcoincore.core.IPluginData
import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.models.RawTransactionBroadcastResult
import io.horizontalsystems.bitcoincore.models.RawTransactionBroadcastStatus
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionDataSortType
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.InputToSign
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoincore.transactions.builder.MutableTransaction
import io.horizontalsystems.bitcoincore.transactions.builder.SignedTransactionData
import io.horizontalsystems.bitcoincore.transactions.builder.TransactionBuilder
import io.horizontalsystems.bitcoincore.transactions.builder.TransactionSigner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

class TransactionCreator(
    private val builder: TransactionBuilder,
    private val processor: PendingTransactionProcessor,
    private val transactionSender: TransactionSender,
    private val transactionSigner: TransactionSigner,
    private val bloomFilterManager: BloomFilterManager,
    private val transactionSerializer: BaseTransactionSerializer,
    // Live existence-check provider for the raw-broadcast path, e.g. Blockchair, used to detect a
    // transaction the network already knows about. Null on chains with no reliable provider.
    private val existenceCheckApi: Api? = null,
    // Overridable for tests only; production callers rely on the default.
    private val existenceCheckTimeoutMillis: Long = EXISTENCE_CHECK_TIMEOUT_MS,
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
        val mutableTransaction =
            builder.buildTransaction(unspentOutput, toAddress, memo, feeRate, sortType, rbfEnabled)

        return create(mutableTransaction)
    }

    suspend fun create(mutableTransaction: MutableTransaction): FullTransaction {
        val fullTransaction = signAndBuild(mutableTransaction)
        processAndSend(fullTransaction)
        return fullTransaction
    }

    suspend fun createSigned(
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
        return signAndBuild(
            builder.buildTransaction(
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
        )
    }

    suspend fun signRawTransaction(
        rawTransaction: ByteArray,
        unspentOutputs: List<UnspentOutput>,
    ): FullTransaction {
        val fullTransaction =
            transactionSerializer.deserialize(BitcoinInputMarkable(rawTransaction))
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
            mutableTransaction.addInput(
                InputToSign(
                    input,
                    unspentOutput.output,
                    unspentOutput.publicKey
                )
            )
        }
        return signAndBuild(mutableTransaction, fullTransaction.inputs)
    }

    fun serialize(transaction: FullTransaction, withWitness: Boolean = true): ByteArray {
        return transactionSerializer.serialize(transaction, withWitness)
    }

    // Relays an already signed, externally produced transaction to the network. It is not persisted
    // as a wallet transaction via processCreated/processAndSend: the wallet does not own this
    // transaction, so storing it there would corrupt balance and history.
    suspend fun broadcastRawTransaction(rawTransactionHex: String): RawTransactionBroadcastResult {
        // Reject malformed input early. hexToByteArray() silently drops a trailing nibble on
        // odd-length strings, so an explicit even-length, hex-only check guards the public API.
        if (!HEX_REGEX.matches(rawTransactionHex)) {
            throw TransactionCreationException("Invalid raw transaction hex")
        }
        val transaction = transactionSerializer.deserialize(
            BitcoinInputMarkable(rawTransactionHex.hexToByteArray())
        )
        if (isAlreadyInNetwork(transaction.header.hash.toReversedHex())) {
            return RawTransactionBroadcastResult(
                transaction,
                RawTransactionBroadcastStatus.AlreadyKnown
            )
        }
        val status = transactionSender.broadcastRawTransaction(transaction, rawTransactionHex)
        return RawTransactionBroadcastResult(transaction, status)
    }

    // Best-effort live lookup to avoid re-broadcasting a transaction the network already has,
    // whether it sits in mempool or is already confirmed. Any failure (no provider for this
    // chain, network error, timeout) must not block the real broadcast, so this fails open.
    //
    // The HTTP call goes through ApiManager, which has a 60s read timeout and no coroutine
    // suspension point of its own - a plain withTimeoutOrNull wrapped around a blocking call
    // would not interrupt it. So the lookup runs on GlobalScope, decoupled from this function's
    // coroutine, and only the `await()` is bounded: the broadcast is guaranteed to proceed after
    // EXISTENCE_CHECK_TIMEOUT_MS regardless of how long the underlying HTTP call actually takes.
    // The lookup coroutine itself may keep running in the background after the timeout; its
    // result is simply discarded.
    private suspend fun isAlreadyInNetwork(txid: String): Boolean {
        val api = existenceCheckApi ?: return false
        val lookup = GlobalScope.async(Dispatchers.IO) {
            try {
                api.getTransactions(listOf(txid)).isNotEmpty()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                false
            }
        }
        return withTimeoutOrNull(existenceCheckTimeoutMillis) { lookup.await() } ?: false
    }

    fun processCreated(transaction: FullTransaction): FullTransaction {
        return processAndSend(transaction)
    }

    fun processCreatedLocally(transaction: FullTransaction): FullTransaction {
        return processCreatedInStorage(transaction)
    }

    fun processRelayedLocally(transaction: FullTransaction): FullTransaction {
        try {
            processor.processRelayed(transaction)
        } catch (ex: BloomFilterManager.BloomFilterExpired) {
            bloomFilterManager.regenerateBloomFilter()
        }

        return transaction
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
        try {
            processCreatedInStorage(transaction)
        } catch (ex: TransactionAlreadyExists) {
            // The transaction may already be in storage if local creation raced with peer/API sync.
            // Still ask the sender to process pending NEW transactions so broadcast retries continue.
        }

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

    private companion object {
        // Non-empty, even length, hex characters only.
        val HEX_REGEX = Regex("^([0-9a-fA-F]{2})+$")

        // Upper bound on how long the raw-broadcast existence pre-check may delay the real
        // broadcast. Kept well under ApiManager's 60s read timeout so a slow/unresponsive API
        // fails open promptly instead of stalling the user-visible broadcast call.
        const val EXISTENCE_CHECK_TIMEOUT_MS = 2500L
    }

}

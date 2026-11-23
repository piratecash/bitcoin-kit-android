package io.horizontalsystems.bitcoincore.rbf

import io.horizontalsystems.bitcoincore.DustCalculator
import io.horizontalsystems.bitcoincore.core.IPublicKeyManager
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.core.PluginManager
import io.horizontalsystems.bitcoincore.extensions.toReversedByteArray
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.managers.UnspentOutputProvider
import io.horizontalsystems.bitcoincore.models.Address
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.models.TransactionType
import io.horizontalsystems.bitcoincore.models.rbfEnabled
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransactionInfo
import io.horizontalsystems.bitcoincore.storage.InputToSign
import io.horizontalsystems.bitcoincore.storage.InputWithPreviousOutput
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoincore.transactions.TransactionConflictsResolver
import io.horizontalsystems.bitcoincore.transactions.TransactionSizeCalculator
import io.horizontalsystems.bitcoincore.transactions.builder.LockTimeSetter
import io.horizontalsystems.bitcoincore.transactions.builder.MutableTransaction
import io.horizontalsystems.bitcoincore.transactions.extractors.TransactionMetadataExtractor
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.bitcoincore.utils.IAddressConverter
import io.horizontalsystems.bitcoincore.utils.ShuffleSorter
import kotlin.math.min

class ReplacementTransactionBuilder(
    private val storage: IStorage,
    private val sizeCalculator: TransactionSizeCalculator,
    private val dustCalculator: DustCalculator,
    private val metadataExtractor: TransactionMetadataExtractor,
    private val pluginManager: PluginManager,
    private val unspentOutputProvider: UnspentOutputProvider,
    private val publicKeyManager: IPublicKeyManager,
    private val conflictsResolver: TransactionConflictsResolver,
    private val transactionSerializer: BaseTransactionSerializer,
    private val lockTimeSetter: LockTimeSetter,
    private val transactionVersion: Int,
    private val addressConverter: IAddressConverter,
    private val changeScriptType: ScriptType
) {

    private fun minReplacementFee(replacedFee: Long, size: Long) = replacedFee + INCREMENTAL_RELAY_FEE_RATE * size

    private fun requiredFee(replacedFee: Long, minFeeRate: Int, size: Long) =
        maxOf((minFeeRate + INCREMENTAL_RELAY_FEE_RATE) * size, minReplacementFee(replacedFee, size))

    // Signatures can be shorter than estimated, so the estimate understates the real rate; stored ones can't help, peer-fetched transactions lack witness.
    private fun originalFeeRate(originalFee: Long, originalSize: Long, inputCount: Int) =
        (originalFee / (originalSize - inputCount)).toInt()

    private fun replacementTransaction(
        minFee: Long,
        replacedFee: Long,
        minFeeRate: Int,
        utxo: List<TransactionOutput>,
        fixedOutputs: List<TransactionOutput>,
        outputs: List<TransactionOutput>
    ): Pair<List<TransactionOutput>, /*Fee*/Long>? {

        val size = sizeCalculator.transactionSize(previousOutputs = utxo, outputs = fixedOutputs + outputs)

        val inputsValue = utxo.sumOf { it.value }
        val outputsValue = (fixedOutputs + outputs).sumOf { it.value }
        val fee = inputsValue - outputsValue
        val required = maxOf(minFee, requiredFee(replacedFee, minFeeRate, size))

        if (fee == required || (fee > required && outputs.isEmpty())) {
            return Pair(outputs, fee)
        }

        if (outputs.isEmpty()) {
            return null
        }

        val output = TransactionOutput(outputs.first())
        output.value = output.value + fee - required

        if (output.value > dustCalculator.dust(output.scriptType)) {
            return Pair(listOf(output) + outputs.drop(1), required)
        }

        return null
    }

    private fun maxSpeedUpFee(
        replacedFee: Long,
        minFeeRate: Int,
        fixedUtxo: List<TransactionOutput>,
        fixedOutputs: List<TransactionOutput>
    ): Long? {
        val unusedUtxo = unspentOutputProvider.getConfirmedSpendableUtxo(UtxoFilters()).sortedBy { it.output.value }

        return (0..unusedUtxo.size).mapNotNull { utxoCount ->
            replacementTransaction(
                minFee = 0,
                replacedFee = replacedFee,
                minFeeRate = minFeeRate,
                utxo = fixedUtxo + unusedUtxo.take(utxoCount).map { it.output },
                fixedOutputs = fixedOutputs,
                outputs = emptyList()
            )?.second
        }.maxOrNull()
    }

    private fun ownOutput(value: Long, address: Address, publicKey: PublicKey) = TransactionOutput(
        value = value,
        index = 0,
        script = address.lockingScript,
        type = address.scriptType,
        address = address.stringValue,
        lockingScriptPayload = address.lockingScriptPayload,
        publicKey = publicKey
    )

    private fun newChangeOutput(value: Long): TransactionOutput {
        val publicKey = publicKeyManager.changePublicKey()

        return ownOutput(value, addressConverter.convert(publicKey, changeScriptType), publicKey)
    }

    private fun incrementedSequence(inputWithPreviousOutput: InputWithPreviousOutput): Long {
        val input = inputWithPreviousOutput.input

        if (inputWithPreviousOutput.previousOutput?.pluginId != null) {
            return pluginManager.incrementedSequence(inputWithPreviousOutput)
        }

        return min(input.sequence + 1, 0xFFFFFFFF)
    }

    private fun inputToSign(previousOutput: TransactionOutput, prevOutputPublicKey: PublicKey, sequence: Long): InputToSign {
        val transactionInput = TransactionInput(previousOutput.transactionHash, previousOutput.index.toLong(), sequence = sequence)

        return InputToSign(transactionInput, previousOutput, prevOutputPublicKey)
    }

    private fun setInputs(
        mutableTransaction: MutableTransaction,
        originalInputs: List<InputWithPreviousOutput>,
        additionalInputs: List<UnspentOutput>
    ) {
        additionalInputs.map { utxo ->
            mutableTransaction.addInput(inputToSign(previousOutput = utxo.output, prevOutputPublicKey = utxo.publicKey, sequence = 0x0))
        }

        pluginManager.processInputs(mutableTransaction)

        originalInputs.map { inputWithPreviousOutput ->
            val previousOutput =
                inputWithPreviousOutput.previousOutput ?: throw BuildError.InvalidTransaction("No previous output of original transaction")
            val publicKey = previousOutput.publicKeyPath?.let { publicKeyManager.getPublicKeyByPath(it) }
                ?: throw BuildError.InvalidTransaction("No public key of original transaction")
            mutableTransaction.addInput(inputToSign(previousOutput = previousOutput, publicKey, incrementedSequence(inputWithPreviousOutput)))
        }
    }

    private fun setOutputs(mutableTransaction: MutableTransaction, outputs: List<TransactionOutput>) {
        val sorted = ShuffleSorter().sortOutputs(outputs)
        sorted.forEachIndexed { index, transactionOutput ->
            transactionOutput.index = index
        }

        mutableTransaction.outputs = sorted
    }

    private fun speedUpReplacement(
        originalFullInfo: FullTransactionInfo,
        minFee: Long,
        replacedFee: Long,
        originalFeeRate: Int,
        fixedUtxo: List<TransactionOutput>
    ): MutableTransaction? {
        // If an output has a pluginId, it most probably has a time-locked value and it shouldn't be altered.
        var fixedOutputs = originalFullInfo.outputs.filter { it.publicKeyPath == null || it.pluginId != null }
        val myOutputs = originalFullInfo.outputs.filter { it.publicKeyPath != null && it.pluginId == null }
        val myChangeOutputs = myOutputs.filter { it.changeOutput }.sortedBy { it.value }
        val myExternalOutputs = myOutputs.filter { !it.changeOutput }.sortedBy { it.value }

        var sortedOutputs = myChangeOutputs + myExternalOutputs
        if (fixedOutputs.isEmpty() && sortedOutputs.isNotEmpty()) {
            fixedOutputs = listOf(sortedOutputs.last())
            sortedOutputs = sortedOutputs.dropLast(1)
        }
        val unusedUtxo = unspentOutputProvider.getConfirmedSpendableUtxo(UtxoFilters()).sortedBy { it.output.value }
        var optimalReplacement: Triple</*inputs*/ List<UnspentOutput>, /*outputs*/ List<TransactionOutput>, /*fee*/ Long>? = null

        fun tryCandidate(utxo: List<UnspentOutput>, outputs: List<TransactionOutput>) {
            replacementTransaction(
                minFee = minFee,
                replacedFee = replacedFee,
                minFeeRate = originalFeeRate,
                utxo = fixedUtxo + utxo.map { it.output },
                fixedOutputs = fixedOutputs,
                outputs = outputs
            )?.let { (outputs, fee) ->
                optimalReplacement.let { _optimalReplacement ->
                    if (_optimalReplacement != null) {
                        if (_optimalReplacement.third > fee) {
                            optimalReplacement = Triple(utxo, outputs, fee)
                        }
                    } else {
                        optimalReplacement = Triple(utxo, outputs, fee)
                    }
                }
            }
        }

        // Stands in for a new change output, so no key is requested unless this candidate wins.
        val changePlaceholder = TransactionOutput().apply { scriptType = changeScriptType }

        var utxoCount = 0
        do {
            val utxo = unusedUtxo.take(utxoCount)
            var outputsCount = sortedOutputs.size

            do {
                tryCandidate(utxo, sortedOutputs.takeLast(outputsCount))

                outputsCount--
            } while (outputsCount >= 0)

            // Tried last: on a fee tie the candidate without a new output must stay the winner.
            if (utxo.isNotEmpty()) {
                tryCandidate(utxo, listOf(changePlaceholder))
            }

            utxoCount++
        } while (utxoCount <= unusedUtxo.size)

        return optimalReplacement?.let { (inputs, outputs, _) ->
            val mutableTransaction = MutableTransaction()
            mutableTransaction.transaction.version = transactionVersion
            setInputs(
                mutableTransaction = mutableTransaction,
                originalInputs = originalFullInfo.inputs,
                additionalInputs = inputs
            )
            setOutputs(
                mutableTransaction = mutableTransaction,
                outputs = fixedOutputs + outputs.map { if (it.publicKeyPath == null) newChangeOutput(it.value) else it }
            )
            lockTimeSetter.setLockTime(mutableTransaction)
            mutableTransaction
        }
    }

    private fun cancelReplacement(
        originalFullInfo: FullTransactionInfo,
        minFee: Long,
        replacedFee: Long,
        originalFeeRate: Int,
        fixedUtxo: List<TransactionOutput>,
        userAddress: Address,
        publicKey: PublicKey
    ): MutableTransaction? {
        val unusedUtxo = unspentOutputProvider.getConfirmedSpendableUtxo(UtxoFilters()).sortedBy { it.output.value }
        val originalInputsValue = fixedUtxo.sumOf { it.value }

        var optimalReplacement: Triple</*inputs*/ List<UnspentOutput>, /*outputs*/ List<TransactionOutput>, /*fee*/ Long>? = null

        var utxoCount = 0
        val outputs = listOf(ownOutput(originalInputsValue - minFee, userAddress, publicKey))
        do {
            if (originalInputsValue - minFee < dustCalculator.dust(userAddress.scriptType)) {
                utxoCount++
                continue
            }

            val utxo = unusedUtxo.take(utxoCount)

            replacementTransaction(
                minFee = minFee,
                replacedFee = replacedFee,
                minFeeRate = originalFeeRate,
                utxo = fixedUtxo + utxo.map { it.output },
                fixedOutputs = listOf(),
                outputs = outputs
            )?.let { (outputs, fee) ->
                optimalReplacement.let { _optimalReplacement ->
                    if (_optimalReplacement != null) {
                        if (_optimalReplacement.third > fee) {
                            optimalReplacement = Triple(utxo, outputs, fee)
                        }
                    } else {
                        optimalReplacement = Triple(utxo, outputs, fee)
                    }
                }
            }

            utxoCount++
        } while (utxoCount <= unusedUtxo.size)


        return optimalReplacement?.let { (inputs, outputs, _) ->
            val mutableTransaction = MutableTransaction()
            mutableTransaction.transaction.version = transactionVersion
            setInputs(
                mutableTransaction = mutableTransaction,
                originalInputs = originalFullInfo.inputs,
                additionalInputs = inputs
            )
            setOutputs(
                mutableTransaction = mutableTransaction,
                outputs = outputs
            )
            mutableTransaction
        }
    }

    fun replacementTransaction(
        transactionHash: String,
        minFee: Long,
        type: ReplacementType
    ): Triple<MutableTransaction, FullTransactionInfo, List<String>> {
        val originalFullInfo = storage.getFullTransactionInfo(transactionHash.toReversedByteArray())
            ?: throw BuildError.InvalidTransaction("No FullTransactionInfo")
        check(originalFullInfo.block == null) { throw BuildError.InvalidTransaction("Transaction already in block") }

        val originalFee = originalFullInfo.metadata.fee
        checkNotNull(originalFee) { throw BuildError.InvalidTransaction("No fee for original transaction") }

        check(originalFullInfo.metadata.type != TransactionType.Incoming) { throw BuildError.InvalidTransaction("Can replace only outgoing transaction") }

        val fixedUtxo = originalFullInfo.inputs.mapNotNull { it.previousOutput }

        check(originalFullInfo.inputs.size == fixedUtxo.size) { throw BuildError.NoPreviousOutput }

        check(originalFullInfo.inputs.any { it.input.rbfEnabled }) { throw BuildError.RbfNotEnabled }

        val originalSize = sizeCalculator.transactionSize(previousOutputs = fixedUtxo, outputs = originalFullInfo.outputs)

        val originalFeeRate = originalFeeRate(originalFee, originalSize, fixedUtxo.size)
        val descendantTransactions = storage.getDescendantTransactionsFullInfo(transactionHash.toReversedByteArray())
        val absoluteFee = descendantTransactions.sumOf { it.metadata.fee ?: 0 }

        check(
            descendantTransactions.all { it.header.conflictingTxHash == null } &&
                    !conflictsResolver.isTransactionReplaced(originalFullInfo.fullTransaction)
        ) {
            throw BuildError.InvalidTransaction("Already replaced")
        }

        check(minReplacementFee(absoluteFee, originalSize) <= minFee) { throw BuildError.FeeTooLow }

        val mutableTransaction = when (type) {
            ReplacementType.SpeedUp -> speedUpReplacement(
                originalFullInfo,
                minFee,
                absoluteFee,
                originalFeeRate,
                fixedUtxo
            )
            is ReplacementType.Cancel -> cancelReplacement(
                originalFullInfo,
                minFee,
                absoluteFee,
                originalFeeRate,
                fixedUtxo,
                type.address,
                type.publicKey
            )
        }

        checkNotNull(mutableTransaction) { throw BuildError.UnableToReplace }

        val fullTransaction = mutableTransaction.build(transactionSerializer)
        metadataExtractor.extract(fullTransaction)
        val metadata = fullTransaction.metadata

        return Triple(
            mutableTransaction,
            FullTransactionInfo(
                block = null,
                header = mutableTransaction.transaction,
                inputs = mutableTransaction.inputsToSign.map {
                    InputWithPreviousOutput(it.input, it.previousOutput)
                },
                outputs = mutableTransaction.outputs,
                metadata = metadata,
                transactionSerializer
            ),
            descendantTransactions.map { it.metadata.transactionHash.toReversedHex() }
        )
    }

    fun replacementInfo(
        transactionHash: String,
        type: ReplacementType
    ): ReplacementTransactionInfo? {
        val originalFullInfo = storage.getFullTransactionInfo(transactionHash.toReversedByteArray()) ?: return null
        check(originalFullInfo.block == null) { throw BuildError.InvalidTransaction("Transaction already in block") }
        check(originalFullInfo.metadata.type != TransactionType.Incoming) { throw BuildError.InvalidTransaction("Can replace only outgoing transaction") }

        val originalFee = originalFullInfo.metadata.fee
        checkNotNull(originalFee) { throw BuildError.InvalidTransaction("No fee for original transaction") }

        val fixedUtxo = originalFullInfo.inputs.mapNotNull { it.previousOutput }
        check(originalFullInfo.inputs.size == fixedUtxo.size) { throw BuildError.NoPreviousOutput }

        val descendantTransactions = storage.getDescendantTransactionsFullInfo(transactionHash.toReversedByteArray())
        val absoluteFee = descendantTransactions.sumOf { it.metadata.fee ?: 0 }

        check(
            descendantTransactions.all { it.header.conflictingTxHash == null } &&
                    !conflictsResolver.isTransactionReplaced(originalFullInfo.fullTransaction)
        ) {
            return null
        }

        val originalSize = sizeCalculator.transactionSize(previousOutputs = fixedUtxo, outputs = originalFullInfo.outputs)
        val originalFeeRate = originalFeeRate(originalFee, originalSize, fixedUtxo.size)

        val replacementTxMinSize: Long
        val maxFeeAmount: Long?

        when (type) {
            ReplacementType.SpeedUp -> {
                var fixedOutputs = originalFullInfo.outputs.filter { it.publicKeyPath == null || it.pluginId != null }
                val myOutputs = originalFullInfo.outputs.filter { it.publicKeyPath != null && it.pluginId == null }
                val myChangeOutputs = myOutputs.filter { it.changeOutput }.sortedBy { it.value }
                val myExternalOutputs = myOutputs.filter { !it.changeOutput }.sortedBy { it.value }

                val sortedOutputs = myChangeOutputs + myExternalOutputs
                if (fixedOutputs.isEmpty() && sortedOutputs.isNotEmpty()) {
                    fixedOutputs = listOf(sortedOutputs.last())
                }

                replacementTxMinSize = sizeCalculator.transactionSize(fixedUtxo, fixedOutputs)
                maxFeeAmount = maxSpeedUpFee(absoluteFee, originalFeeRate, fixedUtxo, fixedOutputs)
            }

            is ReplacementType.Cancel -> {
                val dustValue = dustCalculator.dust(type.address.scriptType).toLong()
                val fixedOutputs = listOf(
                    TransactionOutput(
                        value = dustValue,
                        index = 0,
                        script = type.address.lockingScript,
                        type = type.address.scriptType,
                        address = type.address.stringValue,
                        lockingScriptPayload = type.address.lockingScriptPayload
                    )
                )
                replacementTxMinSize = sizeCalculator.transactionSize(fixedUtxo, fixedOutputs)
                val removableOutputsValue = originalFullInfo.outputs.sumOf { it.value } - dustValue
                val confirmedUtxoTotalValue = unspentOutputProvider.getConfirmedSpendableUtxo(UtxoFilters()).sumOf { it.output.value }
                maxFeeAmount = originalFee + removableOutputsValue + confirmedUtxoTotalValue
            }
        }

        val minFeeAmount = minReplacementFee(absoluteFee, originalSize)

        return if (maxFeeAmount == null || minFeeAmount > maxFeeAmount) {
            null
        } else {
            ReplacementTransactionInfo(replacementTxMinSize, LongRange(minFeeAmount, maxFeeAmount))
        }
    }

    sealed class BuildError : Throwable() {
        class InvalidTransaction(override val message: String) : BuildError()
        object NoPreviousOutput : BuildError()
        object FeeTooLow : BuildError()
        object RbfNotEnabled : BuildError()
        object UnableToReplace : BuildError()
    }

    private companion object {
        // sat/vB. BIP125 rule 4 minimum on Litecoin Core and older Bitcoin Core; also lifts a truncated fee rate above the true one.
        const val INCREMENTAL_RELAY_FEE_RATE = 1L
    }
}

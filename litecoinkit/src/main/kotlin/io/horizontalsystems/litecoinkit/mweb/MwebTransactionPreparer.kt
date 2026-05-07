package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.storage.UnspentOutputInfo
import io.horizontalsystems.bitcoincore.transactions.TransactionSizeCalculator
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressCodec

internal class MwebTransactionPreparer(
    private val addressCodec: MwebAddressCodec,
    private val publicTransactionBridge: MwebPublicTransactionBridge?,
    private val changeAddressProvider: () -> String,
    private val syncStateProvider: () -> MwebSyncState,
    private val utxosProvider: () -> List<MwebUtxo>,
    private val transactionSerializer: BaseTransactionSerializer = BaseTransactionSerializer(),
    private val transactionSizeCalculator: TransactionSizeCalculator = TransactionSizeCalculator(),
) {
    fun prepare(
        request: MwebSendRequest,
        publicOptions: MwebPublicSendOptions,
        dryRun: (rawTemplate: ByteArray, feeRate: Int) -> ByteArray,
    ): PreparedMwebTransaction {
        val selectedMwebUtxos = selectedMwebUtxos(request)
        val publicCandidates = publicCandidates(request, publicOptions)
        val selectedPublicUtxos = mutableListOf<UnspentOutput>()
        var nextPublicCandidateIndex = 0
        var fees = MwebFeeEstimate(normalFee = 0, mwebFee = 0)

        repeat(publicCandidates.size + FEE_ESTIMATION_ATTEMPTS) {
            if (request is MwebSendRequest.PublicToMweb) {
                nextPublicCandidateIndex = addPublicUtxos(
                    selectedPublicUtxos = selectedPublicUtxos,
                    publicCandidates = publicCandidates,
                    nextPublicCandidateIndex = nextPublicCandidateIndex,
                    requiredValue = request.value + fees.total,
                )
            } else {
                validateMwebFunding(request, selectedMwebUtxos, fees.total)
            }

            val draft = buildTransactionDraft(
                request = request,
                selectedPublicUtxos = selectedPublicUtxos,
                selectedMwebUtxos = selectedMwebUtxos,
                fees = fees,
                publicOptions = publicOptions,
            )
            val dryRunTransaction = dryRun(draft.rawTemplate, request.feeRate)
            val estimatedFees = estimateFees(request, draft, dryRunTransaction)
            if (estimatedFees == fees) {
                return draft.prepared(estimatedFees)
            }
            fees = estimatedFees
        }

        throw MwebError.SyncFailure(IllegalStateException("MWEB fee estimation did not converge"))
    }

    private fun selectedMwebUtxos(request: MwebSendRequest): List<MwebUtxo> {
        return when (request) {
            is MwebSendRequest.PublicToMweb -> emptyList()
            is MwebSendRequest.MwebToMweb -> confirmedMwebUtxos()
            is MwebSendRequest.MwebToPublic -> confirmedMwebUtxos()
                .filter { it.confirmations(syncStateProvider().mwebUtxosHeight) >= PEG_OUT_CONFIRMATIONS }
        }
    }

    private fun confirmedMwebUtxos(): List<MwebUtxo> {
        return utxosProvider().filter { it.confirmed && !it.spent }
    }

    private fun publicCandidates(
        request: MwebSendRequest,
        publicOptions: MwebPublicSendOptions,
    ): List<UnspentOutput> {
        if (request !is MwebSendRequest.PublicToMweb) return emptyList()

        return requirePublicBridge()
            .spendableUtxos(publicOptions)
            .sortedWith(
                compareByDescending<UnspentOutput> { it.output.failedToSpend }
                    .thenBy { it.output.value }
            )
    }

    private fun addPublicUtxos(
        selectedPublicUtxos: MutableList<UnspentOutput>,
        publicCandidates: List<UnspentOutput>,
        nextPublicCandidateIndex: Int,
        requiredValue: Long,
    ): Int {
        var candidateIndex = nextPublicCandidateIndex
        while (selectedPublicUtxos.sumOf { it.output.value } < requiredValue) {
            if (candidateIndex >= publicCandidates.size) {
                throw MwebError.InsufficientFunds()
            }
            selectedPublicUtxos.add(publicCandidates[candidateIndex])
            candidateIndex += 1
        }
        return candidateIndex
    }

    private fun validateMwebFunding(
        request: MwebSendRequest,
        selectedMwebUtxos: List<MwebUtxo>,
        fee: Long,
    ) {
        val requiredValue = request.value + fee
        val confirmed = selectedMwebUtxos.sumOf { it.value }
        if (confirmed >= requiredValue) return

        if (confirmed + confirmationPendingMwebValue(request) >= requiredValue) {
            throw MwebError.InsufficientMwebConfirmations()
        }
        throw MwebError.InsufficientFunds()
    }

    private fun confirmationPendingMwebValue(request: MwebSendRequest): Long {
        return when (request) {
            is MwebSendRequest.PublicToMweb -> 0
            is MwebSendRequest.MwebToMweb -> utxosProvider()
                .filter { !it.confirmed && !it.spent }
                .sumOf { it.value }
            is MwebSendRequest.MwebToPublic -> utxosProvider()
                .filter { !it.spent && it.confirmations(syncStateProvider().mwebUtxosHeight) < PEG_OUT_CONFIRMATIONS }
                .sumOf { it.value }
        }
    }

    private fun buildTransactionDraft(
        request: MwebSendRequest,
        selectedPublicUtxos: List<UnspentOutput>,
        selectedMwebUtxos: List<MwebUtxo>,
        fees: MwebFeeEstimate,
        publicOptions: MwebPublicSendOptions,
    ): MwebTransactionDraft {
        val inputValue = selectedPublicUtxos.sumOf { it.output.value } + selectedMwebUtxos.sumOf { it.value }
        val changeValue = inputValue - request.value - fees.total
        if (changeValue < 0) {
            throw MwebError.InsufficientFunds()
        }

        val inputs = publicInputs(selectedPublicUtxos, publicOptions) + mwebInputs(selectedMwebUtxos)
        val outputs = mutableListOf<TransactionOutput>()
        outputs.add(recipientOutput(request, index = outputs.size))
        val changeAddress = if (changeValue > 0) {
            val changeOutput = changeOutput(request, selectedPublicUtxos, changeValue, publicOptions)
            outputs.add(indexedOutput(changeOutput, outputs.size))
            changeOutput.address
        } else {
            null
        }
        val indexedOutputs = outputs.mapIndexed { index, output -> indexedOutput(output, index) }
        val rawTemplate = transactionSerializer.serialize(
            FullTransaction(transactionHeader(), inputs, indexedOutputs, transactionSerializer)
        )
        return MwebTransactionDraft(
            selectedPublicUtxos = selectedPublicUtxos,
            selectedMwebUtxos = selectedMwebUtxos,
            rawTemplate = rawTemplate,
            inputValue = inputValue,
            outputValue = indexedOutputs.sumOf { it.value },
            changeValue = changeValue.takeIf { it > 0 },
            changeAddress = changeAddress,
        )
    }

    private fun publicInputs(
        selectedPublicUtxos: List<UnspentOutput>,
        publicOptions: MwebPublicSendOptions,
    ): List<TransactionInput> {
        val sequence = if (publicOptions.rbfEnabled) RBF_SEQUENCE else DEFAULT_INPUT_SEQUENCE
        return selectedPublicUtxos.map { unspentOutput ->
            TransactionInput(
                previousOutputTxHash = unspentOutput.transaction.hash,
                previousOutputIndex = unspentOutput.output.index.toLong(),
                sequence = sequence,
            )
        }
    }

    private fun mwebInputs(selectedMwebUtxos: List<MwebUtxo>): List<TransactionInput> {
        return selectedMwebUtxos.map { utxo ->
            TransactionInput(
                previousOutputTxHash = utxo.outputId.hexToByteArray(),
                previousOutputIndex = utxo.addressIndex.toLong(),
                sequence = DEFAULT_INPUT_SEQUENCE,
            )
        }
    }

    private fun recipientOutput(request: MwebSendRequest, index: Int): TransactionOutput {
        return when (request) {
            is MwebSendRequest.PublicToMweb,
            is MwebSendRequest.MwebToMweb -> mwebOutput(request.address, request.value, index)
            is MwebSendRequest.MwebToPublic -> requirePublicBridge().output(request.value, request.address)
        }
    }

    private fun changeOutput(
        request: MwebSendRequest,
        selectedPublicUtxos: List<UnspentOutput>,
        changeValue: Long,
        publicOptions: MwebPublicSendOptions,
    ): TransactionOutput {
        return when (request) {
            is MwebSendRequest.PublicToMweb -> requirePublicBridge().changeOutput(
                value = changeValue,
                selectedUtxos = selectedPublicUtxos,
                changeToFirstInput = publicOptions.changeToFirstInput,
            )
            is MwebSendRequest.MwebToPublic,
            is MwebSendRequest.MwebToMweb -> mwebOutput(changeAddressProvider(), changeValue, index = 0)
        }
    }

    private fun mwebOutput(address: String, value: Long, index: Int): TransactionOutput {
        val mwebAddress = addressCodec.decode(address)
        return TransactionOutput(
            value = value,
            index = index,
            script = mwebAddress.scanPublicKey + mwebAddress.spendPublicKey,
            type = ScriptType.UNKNOWN,
            address = mwebAddress.stringValue,
        )
    }

    private fun indexedOutput(output: TransactionOutput, index: Int): TransactionOutput {
        return TransactionOutput(output).apply { this.index = index }
    }

    private fun transactionHeader(): Transaction {
        return Transaction(version = 2, lockTime = 0).apply {
            status = Transaction.Status.NEW
            isMine = true
            isOutgoing = true
        }
    }

    private fun estimateFees(
        request: MwebSendRequest,
        draft: MwebTransactionDraft,
        dryRunTransaction: ByteArray,
    ): MwebFeeEstimate {
        val dryRun = deserialize(dryRunTransaction)
        val postPublicInputValue = publicInputValue(dryRun.inputs, draft.selectedPublicUtxos)
        val mwebInputValue = draft.inputValue - postPublicInputValue
        val expectedPegin = maxOf(0L, draft.outputValue - mwebInputValue)
        val postOutputValue = dryRun.outputs.sumOf { it.value }
        val mwebFee = maxOf(
            0L,
            postOutputValue - expectedPegin + hogExInputFee(request, expectedPegin)
        )
        val normalFee = if (draft.selectedPublicUtxos.isEmpty()) {
            0
        } else {
            transactionSizeCalculator.transactionSize(
                previousOutputs = draft.selectedPublicUtxos.map { it.output },
                outputs = dryRun.outputs,
            ) * request.feeRate
        }
        return MwebFeeEstimate(normalFee = normalFee, mwebFee = mwebFee)
    }

    private fun hogExInputFee(request: MwebSendRequest, expectedPegin: Long): Long {
        if (expectedPegin <= 0) return 0
        return request.feeRate.toLong() * HOGEX_PEGIN_INPUT_VBYTES
    }

    private fun deserialize(rawTransaction: ByteArray): FullTransaction {
        return transactionSerializer.deserialize(BitcoinInputMarkable(rawTransaction))
    }

    private fun publicInputValue(
        inputs: List<TransactionInput>,
        selectedPublicUtxos: List<UnspentOutput>,
    ): Long {
        return inputs.sumOf { input ->
            selectedPublicUtxos.firstOrNull { unspentOutput ->
                unspentOutput.transaction.hash.contentEquals(input.previousOutputTxHash) &&
                    unspentOutput.output.index.toLong() == input.previousOutputIndex
            }?.output?.value ?: 0
        }
    }

    private fun requirePublicBridge(): MwebPublicTransactionBridge {
        return publicTransactionBridge ?: throw MwebError.NativeUnavailable()
    }

    private class MwebTransactionDraft(
        val selectedPublicUtxos: List<UnspentOutput>,
        val selectedMwebUtxos: List<MwebUtxo>,
        val rawTemplate: ByteArray,
        val inputValue: Long,
        val outputValue: Long,
        val changeValue: Long?,
        val changeAddress: String?,
    ) {
        fun prepared(fees: MwebFeeEstimate): PreparedMwebTransaction {
            return PreparedMwebTransaction(
                selectedPublicUtxos = selectedPublicUtxos,
                selectedMwebUtxos = selectedMwebUtxos,
                rawTemplate = rawTemplate,
                normalFee = fees.normalFee,
                mwebFee = fees.mwebFee,
                changeValue = changeValue,
                changeAddress = changeAddress,
            )
        }
    }

    private data class MwebFeeEstimate(
        val normalFee: Long,
        val mwebFee: Long,
    ) {
        val total: Long
            get() = normalFee + mwebFee
    }

    private companion object {
        const val DEFAULT_INPUT_SEQUENCE = 0xfffffffeL
        const val FEE_ESTIMATION_ATTEMPTS = 4
        const val HOGEX_PEGIN_INPUT_VBYTES = 41L
        const val PEG_OUT_CONFIRMATIONS = 6
        const val RBF_SEQUENCE = 0L
    }
}

internal class PreparedMwebTransaction(
    val selectedPublicUtxos: List<UnspentOutput>,
    val selectedMwebUtxos: List<MwebUtxo>,
    val rawTemplate: ByteArray,
    val normalFee: Long,
    val mwebFee: Long,
    val changeValue: Long?,
    val changeAddress: String?,
) {
    fun sendInfo(): MwebSendInfo {
        return MwebSendInfo(
            selectedPublicUtxos = selectedPublicUtxos.map { UnspentOutputInfo.fromUnspentOutput(it) },
            selectedMwebUtxos = selectedMwebUtxos,
            normalFee = normalFee,
            mwebFee = mwebFee,
            totalFee = normalFee + mwebFee,
            changeValue = changeValue,
            changeAddress = changeAddress,
        )
    }
}

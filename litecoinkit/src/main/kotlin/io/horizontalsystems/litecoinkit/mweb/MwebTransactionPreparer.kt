package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
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

        // First pass starts with zero fee assumption and computes the real one;
        // a second pass confirms convergence once the draft uses that fee.
        // For peg-in, growing the public input set may require additional
        // passes — one per added UTXO — so we allow `candidates + 2` iterations.
        repeat(publicCandidates.size + 2) {
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
            val newFees = computeFees(request, draft)
            if (newFees == fees) {
                dryRun(draft.rawTemplate, request.feeRate)
                return validatePrepared(request, draft.prepared(newFees))
            }
            fees = newFees
        }

        throw MwebError.SyncFailure(IllegalStateException("MWEB fee estimation did not converge"))
    }

    private fun validatePrepared(
        request: MwebSendRequest,
        transaction: PreparedMwebTransaction,
    ): PreparedMwebTransaction {
        if (request is MwebSendRequest.PublicToMweb && transaction.selectedPublicUtxos.isEmpty()) {
            throw MwebError.SyncFailure(IllegalStateException("MWEB peg-in requires public inputs"))
        }
        return transaction
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
            outputs = indexedOutputs,
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

    private fun computeFees(
        request: MwebSendRequest,
        draft: MwebTransactionDraft,
    ): MwebFeeEstimate {
        val isPegIn = request is MwebSendRequest.PublicToMweb
        val mwebFee = MwebFeeFormula.estimate(
            outputs = draft.outputs,
            feeRate = request.feeRate,
            isPegIn = isPegIn,
        )
        val normalFee = if (isPegIn) {
            canonicalPegInFeeBytes(draft) * request.feeRate
        } else {
            0L
        }
        return MwebFeeEstimate(normalFee = normalFee, mwebFee = mwebFee)
    }

    private fun canonicalPegInFeeBytes(draft: MwebTransactionDraft): Long {
        return transactionSizeCalculator.transactionSize(
            previousOutputs = draft.selectedPublicUtxos.map { it.output },
            outputs = listOf(PEG_IN_MARKER_OUTPUT),
        ).toLong()
    }

    private fun requirePublicBridge(): MwebPublicTransactionBridge {
        return publicTransactionBridge ?: throw MwebError.NativeUnavailable()
    }

    private class MwebTransactionDraft(
        val selectedPublicUtxos: List<UnspentOutput>,
        val selectedMwebUtxos: List<MwebUtxo>,
        val outputs: List<TransactionOutput>,
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
        const val PEG_OUT_CONFIRMATIONS = 6
        const val RBF_SEQUENCE = 0L
        const val PEG_IN_MARKER_SCRIPT_SIZE = 34
        val PEG_IN_MARKER_OUTPUT: TransactionOutput = TransactionOutput(
            value = 0L,
            index = 0,
            script = ByteArray(PEG_IN_MARKER_SCRIPT_SIZE),
            type = ScriptType.UNKNOWN,
        )
    }
}

/**
 * Local Kotlin port of the MWEB fee formula from ltcd's
 * `mweb.EstimateFee` (ltcutil/mweb/fees.go) plus the HogEx peg-in surcharge
 * documented in the mwebd README. Used as the source of truth for fee
 * estimation, mirroring how Cake Wallet, Electrum-LTC and Litecoin Core
 * compute MWEB fees locally instead of trusting daemon-stripped dry-run output.
 */
internal object MwebFeeFormula {
    private const val BASE_MWEB_FEE = 100L
    private const val KERNEL_WITH_STEALTH_WEIGHT = 3L
    private const val STANDARD_OUTPUT_WEIGHT = 18L
    private const val BYTES_PER_WEIGHT = 42L
    private const val FEE_RATE_KB_PER_VBYTE = 1000L
    private const val MWEB_OUTPUT_SCRIPT_SIZE = 66
    private const val HOGEX_PEGIN_INPUT_VBYTES = 41L
    private const val TX_OUT_VALUE_BYTES = 8

    fun estimate(
        outputs: List<TransactionOutput>,
        feeRate: Int,
        isPegIn: Boolean,
    ): Long {
        var weight = KERNEL_WITH_STEALTH_WEIGHT
        var canonicalTxOutSize = 0L
        for (output in outputs) {
            if (isMwebOutput(output)) {
                weight += STANDARD_OUTPUT_WEIGHT
            } else {
                weight += ceilDiv(output.lockingScript.size.toLong(), BYTES_PER_WEIGHT)
                canonicalTxOutSize += publicTxOutSerializedSize(output).toLong()
            }
        }
        val feeRatePerKb = feeRate.toLong() * FEE_RATE_KB_PER_VBYTE
        val canonicalFeeComponent = ceilDiv(feeRatePerKb * canonicalTxOutSize, FEE_RATE_KB_PER_VBYTE)
        val mwebFeeComponent = weight * BASE_MWEB_FEE
        val hogExSurcharge = if (isPegIn) feeRate.toLong() * HOGEX_PEGIN_INPUT_VBYTES else 0L
        return canonicalFeeComponent + mwebFeeComponent + hogExSurcharge
    }

    fun isMwebOutput(output: TransactionOutput): Boolean {
        return output.scriptType == ScriptType.UNKNOWN &&
            output.lockingScript.size == MWEB_OUTPUT_SCRIPT_SIZE
    }

    private fun publicTxOutSerializedSize(output: TransactionOutput): Int {
        val scriptSize = output.lockingScript.size
        return TX_OUT_VALUE_BYTES + varintSize(scriptSize) + scriptSize
    }

    private fun varintSize(value: Int): Int = when {
        value < 0xfd -> 1
        value <= 0xffff -> 3
        value.toLong() <= 0xffffffffL -> 5
        else -> 9
    }

    private fun ceilDiv(numerator: Long, denominator: Long): Long {
        return (numerator + denominator - 1) / denominator
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

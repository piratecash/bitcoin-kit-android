package io.horizontalsystems.bitcoincore.transactions.builder

import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.storage.InputToSign
import io.horizontalsystems.bitcoincore.transactions.scripts.OpCodes
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType

class TransactionSigner(
    private val ecdsaInputSigner: IInputSigner,
    private val schnorrInputSigner: ISchnorrInputSigner
) {
    suspend fun sign(mutableTransaction: MutableTransaction) {
        // Bunch sign added to support Tangem sdk
        if (isAllOutputsOneType(mutableTransaction)) {
            if (batchSign(mutableTransaction)) return
        }

        mutableTransaction.inputsToSign.forEachIndexed { index, inputToSign ->
            if (inputToSign.previousOutput.scriptType == ScriptType.P2TR) {
                schnorrSign(index, mutableTransaction)
            } else {
                ecdsaSign(index, mutableTransaction)
            }
        }
    }

    private suspend fun batchSign(mutableTransaction: MutableTransaction): Boolean {
        val scriptType =
            mutableTransaction.inputsToSign.firstOrNull()?.previousOutput?.scriptType ?: false
        if (scriptType == ScriptType.P2TR) {
            batchSchnorrSign(
                mutableTransaction,
                schnorrInputSigner as? ISchnorrInputBatchSigner ?: return false
            )
        } else {
            batchEcdsaSign(
                mutableTransaction,
                ecdsaInputSigner as? IInputBatchSigner ?: return false
            )
        }
        return false
    }

    private suspend fun batchSchnorrSign(
        mutableTransaction: MutableTransaction,
        schnorrInputSigner: ISchnorrInputBatchSigner
    ) {
        val dataToSign = schnorrInputSigner.prepareDataForSchnorrSigning(mutableTransaction)
        val signatures = schnorrInputSigner.sigScriptSchnorrData(dataToSign)
        mutableTransaction.inputsToSign.forEachIndexed { index, inputToSign ->
            val inputToSign = mutableTransaction.inputsToSign[index]
            val previousOutput = inputToSign.previousOutput

            if (previousOutput.scriptType != ScriptType.P2TR) {
                throw TransactionBuilder.BuilderException.NotSupportedScriptType()
            }

            mutableTransaction.transaction.segwit = true
            signatures.getOrNull(index)?.let { witnessData ->
                inputToSign.input.witness = listOf(witnessData)
            }
        }
    }

    private suspend fun batchEcdsaSign(
        mutableTransaction: MutableTransaction,
        iInputBatchSigner: IInputBatchSigner
    ) {
        val dataToSign = iInputBatchSigner.prepareDataForEcdsaSigning(mutableTransaction)
        val signatures = iInputBatchSigner.sigScriptEcdsaData(dataToSign)
        mutableTransaction.inputsToSign.forEachIndexed { index, inputToSign ->
            val inputToSign = mutableTransaction.inputsToSign[index]
            val previousOutput = inputToSign.previousOutput
            val publicKey = inputToSign.previousOutputPublicKey
            val sigScriptData = signatures.getOrNull(index) ?: throw IllegalStateException(
                "Signature data for input at index $index is null"
            )
            signOutput(previousOutput, inputToSign, sigScriptData, mutableTransaction, publicKey)
        }
    }

    private fun signOutput(
        previousOutput: TransactionOutput,
        inputToSign: InputToSign,
        sigScriptData: List<ByteArray>,
        mutableTransaction: MutableTransaction,
        publicKey: PublicKey
    ) {
        when (previousOutput.scriptType) {
            ScriptType.P2PKH -> {
                inputToSign.input.sigScript = signatureScript(sigScriptData)
            }

            ScriptType.P2WPKH -> {
                mutableTransaction.transaction.segwit = true
                inputToSign.input.witness = sigScriptData
            }

            ScriptType.P2WPKHSH -> {
                mutableTransaction.transaction.segwit = true
                val witnessProgram = OpCodes.scriptWPKH(publicKey.publicKeyHash)

                inputToSign.input.sigScript = signatureScript(listOf(witnessProgram))
                inputToSign.input.witness = sigScriptData
            }

            ScriptType.P2SH -> {
                val redeemScript = previousOutput.redeemScript ?: throw NoRedeemScriptException()
                val signatureScriptFunction = previousOutput.signatureScriptFunction

                if (signatureScriptFunction != null) {
                    // non-standard P2SH signature script
                    inputToSign.input.sigScript = signatureScriptFunction(sigScriptData)
                } else {
                    // standard (signature, publicKey, redeemScript) signature script
                    inputToSign.input.sigScript = signatureScript(sigScriptData + redeemScript)
                }
            }

            else -> throw TransactionBuilder.BuilderException.NotSupportedScriptType()
        }
    }

    private fun isAllOutputsOneType(mutableTransaction: MutableTransaction) =
        mutableTransaction.inputsToSign.map { it.previousOutput.scriptType }.distinct().size == 1

    private suspend fun schnorrSign(index: Int, mutableTransaction: MutableTransaction) {
        val inputToSign = mutableTransaction.inputsToSign[index]
        val previousOutput = inputToSign.previousOutput

        if (previousOutput.scriptType != ScriptType.P2TR) {
            throw TransactionBuilder.BuilderException.NotSupportedScriptType()
        }

        val witnessData = schnorrInputSigner.sigScriptSchnorrData(
            mutableTransaction.transaction,
            mutableTransaction.inputsToSign,
            mutableTransaction.outputs,
            index
        )

        mutableTransaction.transaction.segwit = true
        inputToSign.input.witness = witnessData
    }

    private suspend fun ecdsaSign(index: Int, mutableTransaction: MutableTransaction) {
        val inputToSign = mutableTransaction.inputsToSign[index]
        val previousOutput = inputToSign.previousOutput
        val publicKey = inputToSign.previousOutputPublicKey
        val sigScriptData = ecdsaInputSigner.sigScriptEcdsaData(
            mutableTransaction.transaction,
            mutableTransaction.inputsToSign,
            mutableTransaction.outputs,
            index
        )
        signOutput(
            previousOutput = previousOutput,
            inputToSign = inputToSign,
            sigScriptData = sigScriptData,
            mutableTransaction = mutableTransaction,
            publicKey = publicKey
        )
    }

    private fun signatureScript(params: List<ByteArray>): ByteArray {
        return params.fold(byteArrayOf()) { acc, bytes -> acc + OpCodes.push(bytes) }
    }
}

class NoRedeemScriptException : Exception()

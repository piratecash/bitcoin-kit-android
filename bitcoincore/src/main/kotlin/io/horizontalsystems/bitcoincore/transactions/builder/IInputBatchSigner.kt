package io.horizontalsystems.bitcoincore.transactions.builder

interface IInputBatchSigner {
    suspend fun prepareDataForSigning(mutableTransaction: MutableTransaction): List<ByteArray>

    suspend fun sigScriptEcdsaData(data: List<ByteArray>): List<List<ByteArray>>
}
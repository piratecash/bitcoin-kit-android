package io.horizontalsystems.bitcoincore.transactions.builder

interface ISchnorrInputBatchSigner {
    suspend fun prepareDataForSchnorrSigning(mutableTransaction: MutableTransaction): List<ByteArray>

    suspend fun sigScriptSchnorrData(data: List<ByteArray>): List<ByteArray>
}
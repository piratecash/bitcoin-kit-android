package io.horizontalsystems.bitcoincore.transactions.builder

import io.horizontalsystems.bitcoincore.transactions.model.DataToSign

interface ISchnorrInputBatchSigner {
    suspend fun prepareDataForSchnorrSigning(mutableTransaction: MutableTransaction): List<DataToSign>

    suspend fun sigScriptSchnorrData(data: List<DataToSign>): List<ByteArray>
}
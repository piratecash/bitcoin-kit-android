package io.horizontalsystems.bitcoincore.transactions.builder

import io.horizontalsystems.bitcoincore.transactions.model.DataToSign

interface IEcdsaInputBatchSigner {
    suspend fun prepareDataForEcdsaSigning(mutableTransaction: MutableTransaction): List<DataToSign>

    suspend fun sigScriptEcdsaData(data: List<DataToSign>): List<List<ByteArray>>
}
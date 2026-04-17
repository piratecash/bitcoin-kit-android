package io.horizontalsystems.bitcoincore.transactions.builder

data class SignedTransactionData(
    val serializedTx: String,
    val signatures: List<String>
)

interface IFullTransactionSigner {
    suspend fun signFullTransaction(mutableTransaction: MutableTransaction): SignedTransactionData
}

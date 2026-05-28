package io.horizontalsystems.bitcoincore.transactions.builder

/**
 * Complete signature result returned by a kit-specific signer.
 *
 * @property serializedTx fully serialized raw transaction encoded as lowercase or uppercase hex.
 * @property signatures optional signer-specific signature payloads encoded as hex strings.
 */
data class SignedTransactionData(
    val serializedTx: String,
    val signatures: List<String>
)

/**
 * Signs a whole mutable transaction when input-level signing is not enough for a coin.
 *
 * Implementations must be thread-safe for concurrent send attempts from the owning kit.
 */
interface IFullTransactionSigner {
    suspend fun signFullTransaction(mutableTransaction: MutableTransaction): SignedTransactionData
}

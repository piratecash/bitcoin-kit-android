package io.horizontalsystems.bitcoincore.apisync.blockchair
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class BlockchairTransactionResponse(
    val data: Map<String, FullApiTransaction>
)

@Serializable
data class FullApiTransaction(
    val transaction: ApiTransaction,
    val inputs: List<ApiInput>,
    val outputs: List<ApiOutput>
)

@Serializable
data class ApiTransaction(
    val hash: String,
    val date: String,
    val time: String,
    val fee: Long
)

@Serializable
data class ApiInput(
    val recipient: String,
    @SerialName("transaction_hash")
    val transactionHash: String,
    @SerialName("spending_transaction_hash")
    val spendingTransactionHash: String,
    @SerialName("spending_sequence")
    val spendingSequence: Long,
    @SerialName("script_hex")
    val scriptHex: String,
    val value: Long
)

@Serializable
data class ApiOutput(
    val recipient: String,
    val value: Long
)

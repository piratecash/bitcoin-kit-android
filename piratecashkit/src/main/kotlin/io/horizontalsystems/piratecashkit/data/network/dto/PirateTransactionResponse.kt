package io.horizontalsystems.piratecashkit.data.network.dto

import io.horizontalsystems.bitcoincore.apisync.blockchair.FullApiTransaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PirateTransactionResponse(
    val active: String,
    val tx: Tx,
    val confirmations: Long,
    val blockcount: Long
)

@Serializable
data class Tx(
    @SerialName("_id")
    val id: String,
    val txid: String,
    val vin: List<Vin>,
    val vout: List<Vout>,
    val total: Long,
    val timestamp: Long,
    val blockhash: String,
    val blockindex: Long,
    val tx_type: String? = null,
    val op_return: String? = null,
    val algo: String? = null,
    @SerialName("__v")
    val v: Int
)

@Serializable
data class Vin(
    val addresses: String,
    val amount: Long
)

@Serializable
data class Vout(
    val addresses: String,
    val amount: Long
)

fun Tx.toFullApiTransaction(): FullApiTransaction =
    FullApiTransaction(
        transaction = io.horizontalsystems.bitcoincore.apisync.blockchair.ApiTransaction(
            hash = txid,
            date = "", // Not provided by PirateCash API
            time = timestamp.toString(),
            fee = 0L // Not provided by PirateCash API
        ),
        inputs = vin.map {
            io.horizontalsystems.bitcoincore.apisync.blockchair.ApiInput(
                recipient = it.addresses,
                transactionHash = "",
                spendingTransactionHash = "",
                spendingSequence = 0L,
                scriptHex = "",
                value = it.amount
            )
        },
        outputs = vout.map {
            io.horizontalsystems.bitcoincore.apisync.blockchair.ApiOutput(
                recipient = it.addresses,
                value = it.amount
            )
        }
    )

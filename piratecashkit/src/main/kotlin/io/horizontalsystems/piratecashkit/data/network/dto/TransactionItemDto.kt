package io.horizontalsystems.piratecashkit.data.network.dto

import io.horizontalsystems.bitcoincore.apisync.model.AddressItem
import io.horizontalsystems.bitcoincore.apisync.model.TransactionItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal class TransactionItemDto(
    @SerialName("tx") val tx: TxDto,
    @SerialName("confirmations") val confirmations: Int,
    @SerialName("blockcount") val blockCount: Int
)

@Serializable
internal class TxDto(
    @SerialName("_id") val id: String,
    @SerialName("txid") val txId: String,
    @SerialName("vin") val vin: List<VinDto>,
    @SerialName("vout") val vout: List<VoutDto>,
    @SerialName("total") val total: Long,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("blockhash") val blockHash: String,
    @SerialName("blockindex") val blockIndex: Int,
    @SerialName("tx_type") val txType: String? = null,
    @SerialName("op_return") val opReturn: String? = null,
    @SerialName("algo") val algo: String? = null,
)

@Serializable
internal class VinDto(
    @SerialName("addresses") val address: String,
    @SerialName("amount") val amount: Long
)

@Serializable
internal class VoutDto(
    @SerialName("addresses") val address: String,
    @SerialName("amount") val amount: Long
)

internal fun TransactionItemDto.toTransactionItem(): TransactionItem {
    val addressItems = mutableListOf<AddressItem>()

    tx.vout.forEach { vout ->
        addressItems.add(AddressItem(script = "", address = vout.address))
    }

    tx.vin.forEach { vin ->
        addressItems.add(AddressItem(script = "", address = vin.address))
    }

    return TransactionItem(
        blockHash = tx.blockHash,
        blockHeight = tx.blockIndex,
        addressItems = addressItems
    )
}
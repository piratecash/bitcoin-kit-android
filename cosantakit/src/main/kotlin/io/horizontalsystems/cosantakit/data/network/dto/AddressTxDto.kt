package io.horizontalsystems.cosantakit.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal class AddressTxDto(
    @SerialName("timestamp")
    val timestamp: Long,

    @SerialName("txid")
    val txid: String,

    @SerialName("sent")
    val sent: Double,

    @SerialName("received")
    val received: Double,

    @SerialName("balance")
    val balance: Double
)

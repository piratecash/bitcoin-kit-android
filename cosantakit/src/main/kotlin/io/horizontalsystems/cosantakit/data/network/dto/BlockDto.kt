package io.horizontalsystems.cosantakit.data.network.dto

import io.horizontalsystems.bitcoincore.apisync.model.BlockHeaderItem
import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal class BlockDto(
    @SerialName("hash") val hash: String,
    @SerialName("confirmations") val confirmations: Int,
    @SerialName("size") val size: Int,
    @SerialName("height") val height: Int,
    @SerialName("version") val version: Int,
    @SerialName("versionHex") val versionHex: String,
    @SerialName("merkleroot") val merkleRoot: String,
    @SerialName("tx") val tx: List<String>,
    @SerialName("cbTx") val cbTx: CbTxDto,
    @SerialName("time") val time: Long,
    @SerialName("mediantime") val mediantime: Long,
    @SerialName("nonce") val nonce: Long,
    @SerialName("bits") val bits: String,
    @SerialName("difficulty") val difficulty: Double,
    @SerialName("chainwork") val chainwork: String,
    @SerialName("nTx") val nTx: Int,
    @SerialName("previousblockhash") val previousBlockHash: String? = null,
    @SerialName("nextblockhash") val nextBlockHash: String? = null,
    @SerialName("chainlock") val chainlock: Boolean
)

@Serializable
internal class CbTxDto(
    @SerialName("version") val version: Int,
    @SerialName("height") val height: Int,
    @SerialName("merkleRootMNList") val merkleRootMNList: String,
    @SerialName("merkleRootQuorums") val merkleRootQuorums: String
)

internal fun BlockDto.toBlockHeaderItem(): BlockHeaderItem {
    return BlockHeaderItem(
        hash = hash.hexToByteArray().reversedArray(),
        height = height,
        timestamp = time
    )
}

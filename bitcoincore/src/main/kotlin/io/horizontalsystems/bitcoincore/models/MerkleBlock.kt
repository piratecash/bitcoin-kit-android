package io.horizontalsystems.bitcoincore.models

import io.horizontalsystems.bitcoincore.core.HashBytes
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import kotlinx.serialization.Transient
import kotlinx.serialization.Serializable

@Serializable
class MerkleBlock(
    val header: BlockHeader,
    val associatedTransactionHashes: Map<HashBytes, Boolean>,
    @Transient
    val extraData: Any? = null
) {

    var height: Int? = null
    @Transient
    var associatedTransactions = mutableListOf<FullTransaction>()
    val blockHash = header.hash

    val complete: Boolean
        get() = associatedTransactionHashes.size == associatedTransactions.size
}

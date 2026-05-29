package io.horizontalsystems.bitcoincore.network.messages

import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.horizontalsystems.bitcoincore.storage.FullTransaction

class BlockMessage(
    val header: BlockHeader,
    val transactions: List<FullTransaction>,
    val size: Int
) : IMessage {
    private val blockHash: String by lazy {
        header.hash.toReversedHex()
    }

    override fun toString(): String {
        return "BlockMessage(blockHash=$blockHash, transactions=${transactions.size})"
    }
}

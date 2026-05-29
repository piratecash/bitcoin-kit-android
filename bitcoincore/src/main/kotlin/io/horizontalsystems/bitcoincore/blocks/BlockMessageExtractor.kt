package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.core.HashBytes
import io.horizontalsystems.bitcoincore.models.MerkleBlock
import io.horizontalsystems.bitcoincore.network.messages.BlockMessage
import io.horizontalsystems.bitcoincore.utils.MerkleRoot

class BlockMessageExtractor(private val maxBlockSize: Int) {

    fun extract(message: BlockMessage): MerkleBlock {
        val txCount = message.transactions.size
        if (txCount < 1 || txCount > maxBlockSize / MIN_TRANSACTION_SIZE) {
            throw InvalidMerkleBlockException(String.format("Transaction count %d is not valid", txCount))
        }

        val transactionHashes = message.transactions.map { it.header.hash }
        val merkleRoot = MerkleRoot.calculate(transactionHashes)
        if (!message.header.merkleRoot.contentEquals(merkleRoot)) {
            throw InvalidMerkleBlockException("Merkle root is not valid")
        }

        return MerkleBlock(
            header = message.header,
            associatedTransactionHashes = transactionHashes.associate { HashBytes(it) to true }
        ).apply {
            associatedTransactions.addAll(message.transactions)
        }
    }

    private companion object {
        const val MIN_TRANSACTION_SIZE = 60
    }
}

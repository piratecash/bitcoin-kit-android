package io.horizontalsystems.bitcoincore.message

import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.network.messages.BlockMessage
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.horizontalsystems.bitcoincore.storage.FullTransaction

object BlockMessageTestData {
    val transactionHash = ByteArray(32) { index -> (index + 1).toByte() }
    val blockHash = ByteArray(32) { index -> (index + 33).toByte() }

    fun transaction(hash: ByteArray = transactionHash): FullTransaction {
        return FullTransaction(Transaction(), emptyList(), emptyList(), BaseTransactionSerializer(), false)
            .apply { setHash(hash) }
    }

    fun header(
        hash: ByteArray = blockHash,
        merkleRoot: ByteArray = transactionHash
    ): BlockHeader {
        return BlockHeader(
            version = 1,
            previousBlockHeaderHash = ByteArray(32),
            merkleRoot = merkleRoot,
            timestamp = 1L,
            bits = 1L,
            nonce = 1L,
            hash = hash
        )
    }

    fun blockMessage(
        header: BlockHeader = header(),
        transactions: List<FullTransaction> = listOf(transaction())
    ): BlockMessage {
        return BlockMessage(header, transactions, size = 128)
    }
}

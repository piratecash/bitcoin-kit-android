package io.horizontalsystems.ecash.messages

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.network.messages.BlockMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessageParser
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.serializers.BlockHeaderParser
import java.io.IOException

class ECashBlockMessageParser(
    private val blockHeaderParser: BlockHeaderParser,
    private val transactionSerializer: BaseTransactionSerializer,
    private val maxBlockSize: Int
) : IMessageParser {
    override val command = "block"

    override fun parseMessage(input: BitcoinInputMarkable): IMessage {
        val header = blockHeaderParser.parse(input)
        val transactionCount = input.readVarInt()
        if (transactionCount < 1 || transactionCount > maxBlockSize / MIN_TRANSACTION_SIZE) {
            throw IOException("block transaction count $transactionCount is not valid")
        }

        val transactions = List(transactionCount.toInt()) {
            transactionSerializer.deserialize(input)
        }

        return BlockMessage(header, transactions, input.count)
    }

    private companion object {
        const val MIN_TRANSACTION_SIZE = 60
    }
}

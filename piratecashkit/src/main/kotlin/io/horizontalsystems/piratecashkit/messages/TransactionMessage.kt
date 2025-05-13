package io.horizontalsystems.piratecashkit.messages

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessageParser
import io.horizontalsystems.bitcoincore.network.messages.TransactionMessage
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer

internal class TransactionMessageParser(private val transactionSerializer: BaseTransactionSerializer) : IMessageParser {
    override val command: String = "tx"

    override fun parseMessage(input: BitcoinInputMarkable): IMessage {

        var transaction = transactionSerializer.deserialize(input)
        return TransactionMessage(transaction, input.count)
    }

}

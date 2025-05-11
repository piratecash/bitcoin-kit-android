package io.horizontalsystems.piratecashkit.messages

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessageParser
import io.horizontalsystems.bitcoincore.network.messages.TransactionMessage
import io.horizontalsystems.bitcoincore.serializers.TransactionSerializerProvider

internal class TransactionMessageParser : IMessageParser {
    override val command: String = "tx"

    override fun parseMessage(input: BitcoinInputMarkable): IMessage {

        var transaction = TransactionSerializerProvider.deserialize(input)
        return TransactionMessage(transaction, input.count)
    }

}

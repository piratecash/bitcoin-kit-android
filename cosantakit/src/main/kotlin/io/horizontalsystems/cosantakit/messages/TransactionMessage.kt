package io.horizontalsystems.cosantakit.messages

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessageParser
import io.horizontalsystems.bitcoincore.network.messages.TransactionMessage
import io.horizontalsystems.cosantakit.models.CosantaTransactionSerializer

internal class TransactionMessageParser : IMessageParser {
    override val command: String = "tx"

    override fun parseMessage(input: BitcoinInputMarkable): IMessage {

        var transaction = CosantaTransactionSerializer.deserialize(input)
        return TransactionMessage(transaction, input.count)
    }

}

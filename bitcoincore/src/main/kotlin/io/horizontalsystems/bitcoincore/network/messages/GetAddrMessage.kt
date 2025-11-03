package io.horizontalsystems.bitcoincore.network.messages

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable

class GetAddrMessage : IMessage {
    override fun toString(): String = "GetAddrMessage"
}

class GetAddrMessageParser : IMessageParser {
    override val command = "getaddr"
    override fun parseMessage(input: BitcoinInputMarkable): IMessage {
        return GetAddrMessage()
    }
}

class GetAddrMessageSerializer : IMessageSerializer {
    override val command: String = "getaddr"
    override fun serialize(message: IMessage): ByteArray? {
        return null // getaddr has no data
    }
} 
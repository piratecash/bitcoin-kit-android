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
        return ByteArray(0) // getaddr has no data
    }
} 
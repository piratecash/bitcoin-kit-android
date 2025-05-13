package io.horizontalsystems.piratecashkit.messages

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessageParser
import io.horizontalsystems.bitcoincore.network.messages.MerkleBlockMessage
import java.io.IOException

internal class PirateCashCoinMerkleBlockMessage(private val blockHeaderParser: PirateCashBlockHeaderParser) :
    IMessageParser {
    override val command = "merkleblock"

    override fun parseMessage(input: BitcoinInputMarkable): IMessage {
        val header = blockHeaderParser.parse(input)

        val txCount = input.readInt()
        val hashCount = input.readVarInt().toInt()
        val hashes = mutableListOf<ByteArray>()
        repeat(hashCount) {
            hashes.add(input.readBytes(32))
        }
        val flagsCount = input.readVarInt().toInt()
        if (flagsCount > input.available()) {
            throw IOException("Bad merkleblock: flagsCount=$flagsCount but only ${input.available()} bytes left")
        }
        val flags = input.readBytes(flagsCount)
        return MerkleBlockMessage(header, txCount, hashCount, hashes, flagsCount, flags)
    }
}

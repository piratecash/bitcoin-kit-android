package cash.p.dogecoinkit.messages

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessageParser
import io.horizontalsystems.bitcoincore.network.messages.MerkleBlockMessage
import cash.p.dogecoinkit.serializers.AuxPowSerializer
import io.horizontalsystems.bitcoincore.serializers.BlockHeaderParser
import java.io.IOException

class DogeCoinMerkleBlockMessageParser(private val blockHeaderParser: BlockHeaderParser) : IMessageParser {
    override val command = "merkleblock"

    override fun parseMessage(input: BitcoinInputMarkable): IMessage {
        val header = blockHeaderParser.parse(input)

        val auxPoWMessage = if (isAuxBlock(header.version.toLong())) {
            AuxPowSerializer.deserialize(input)
            // No need this data, just parse it to skip
        } else {
            null
        }

        val txCount = input.readInt()

        input.mark()
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
        return MerkleBlockMessage(
            header = header,
            txCount = txCount,
            hashCount = hashCount,
            hashes = hashes,
            flagsCount = flagsCount,
            flags = flags,
            extraData = auxPoWMessage
        )
    }

    private fun isAuxBlock(version: Long): Boolean {
        val minauxversion = 0x02
        val isauxpow = 0x6201
        val versionmask = 0xff
        return (version shr 8) == isauxpow.toLong() && (version and versionmask.toLong()) >= minauxversion
    }
}

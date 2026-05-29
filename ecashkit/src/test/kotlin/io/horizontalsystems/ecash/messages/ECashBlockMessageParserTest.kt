package io.horizontalsystems.ecash.messages

import io.horizontalsystems.bitcoincore.core.DoubleSha256Hasher
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.network.messages.BlockMessage
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.serializers.BlockHeaderParser
import io.horizontalsystems.bitcoincore.utils.HashUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ECashBlockMessageParserTest {

    private val transactionSerializer = BaseTransactionSerializer()
    private val parser = ECashBlockMessageParser(
        BlockHeaderParser(DoubleSha256Hasher()),
        transactionSerializer,
        maxBlockSize = 32 * 1024 * 1024
    )

    @Test
    fun parseMessage_validBlock_returnsBlockMessage() {
        val transactionPayload = transactionPayload()
        val transactionHash = HashUtils.doubleSha256(transactionPayload)
        val payload = blockPayload(transactionHash, transactionPayload)

        val message = parser.parseMessage(BitcoinInputMarkable(payload)) as BlockMessage

        assertArrayEquals(transactionHash, message.header.merkleRoot)
        assertEquals(1, message.transactions.size)
        assertArrayEquals(transactionHash, message.transactions.single().header.hash)
        assertEquals(payload.size, message.size)
    }

    @Test(expected = IOException::class)
    fun parseMessage_zeroTransactions_throws() {
        val payload = BitcoinOutput()
            .write(headerPayload(ByteArray(32)))
            .writeVarInt(0L)
            .toByteArray()

        parser.parseMessage(BitcoinInputMarkable(payload))
    }

    private fun blockPayload(merkleRoot: ByteArray, transactionPayload: ByteArray): ByteArray {
        return BitcoinOutput()
            .write(headerPayload(merkleRoot))
            .writeVarInt(1L)
            .write(transactionPayload)
            .toByteArray()
    }

    private fun headerPayload(merkleRoot: ByteArray): ByteArray {
        return BitcoinOutput()
            .writeInt(1)
            .write(ByteArray(32))
            .write(merkleRoot)
            .writeUnsignedInt(1L)
            .writeUnsignedInt(0x1d00ffffL)
            .writeUnsignedInt(0L)
            .toByteArray()
    }

    private fun transactionPayload(): ByteArray {
        return BitcoinOutput()
            .writeInt(1)
            .writeVarInt(1L)
            .write(ByteArray(32))
            .writeUnsignedInt(0xffffffffL)
            .writeVarInt(1L)
            .writeByte(0x51)
            .writeUnsignedInt(0xffffffffL)
            .writeVarInt(1L)
            .writeLong(0L)
            .writeVarInt(1L)
            .writeByte(0x51)
            .writeUnsignedInt(0L)
            .toByteArray()
    }
}

package io.horizontalsystems.bitcoincore.network.messages

import io.horizontalsystems.bitcoincore.core.IHasher
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HeadersMessageParserTest {

    private val parser = HeadersMessageParser(object : IHasher {
        override fun hash(data: ByteArray) = data.copyOf(4)
    })

    @Test
    fun `parseMessage - count within the protocol maximum - parses every header`() {
        val message = parser.parseMessage(BitcoinInputMarkable(payload(headerCount = 2)))

        assertEquals(2, (message as HeadersMessage).headers.size)
    }

    @Test
    fun `parseMessage - count above the protocol maximum - is rejected before allocating`() {
        val payload = BitcoinOutput().writeVarInt(HeadersMessageParser.MAX_HEADERS + 1).toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            parser.parseMessage(BitcoinInputMarkable(payload))
        }
    }

    @Test
    fun `parseMessage - count claims the whole address space - is rejected before allocating`() {
        val payload = BitcoinOutput().writeVarInt(Int.MAX_VALUE.toLong()).toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            parser.parseMessage(BitcoinInputMarkable(payload))
        }
    }

    private fun payload(headerCount: Int): ByteArray {
        val output = BitcoinOutput().writeVarInt(headerCount.toLong())

        repeat(headerCount) { index ->
            output.writeInt(1)
                .write(ByteArray(32) { index.toByte() })
                .write(ByteArray(32))
                .writeUnsignedInt(0)
                .writeUnsignedInt(0)
                .writeUnsignedInt(0)
                .writeVarInt(0)
        }

        return output.toByteArray()
    }
}

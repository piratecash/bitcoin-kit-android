package io.horizontalsystems.bitcoincore.network.messages

import io.horizontalsystems.bitcoincore.io.BitcoinInput
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * NetworkMessageParser previously read `payloadLength` as a signed int from
 * an untrusted peer and immediately allocated `ByteArray(payloadLength)`,
 * bypassing the cap on [BitcoinInput.readBytes]. A peer that sends a
 * `payloadLength` of several hundred MB triggered an unrecoverable
 * OutOfMemoryError on the worker thread, identical in shape to the
 * MasternodeListDiff OOM we already patched.
 *
 * Routing the allocation through BitcoinInput.readBytes (which enforces
 * MAX_READ_BYTES) keeps a single chokepoint and surfaces the rejection as
 * a recoverable IOException so the peer-loop can disconnect cleanly.
 */
class NetworkMessageParserPayloadLimitTest {

    private val magic = 0xD9B4BEF9L

    @Test
    fun parseMessage_payloadLengthAboveCap_throwsIOExceptionBeforeAllocating() {
        // Craft a header that declares a 50 MB payload (above the 32 MB cap).
        // The body is intentionally absent — without the cap the parser would
        // allocate the buffer up front and we'd bottom out on EOFException
        // (or OOM on heap-constrained devices).
        val header = ByteArrayOutputStream().apply {
            writeUInt32LE(magic)                  // magic
            write(commandBytes("version"))        // command (12 bytes)
            writeInt32LE(50 * 1024 * 1024)        // payloadLength = 50 MB
            write(ByteArray(4))                   // checksum (4 bytes)
        }.toByteArray()

        val parser = NetworkMessageParser(magic)
        try {
            parser.parseMessage(BitcoinInput(ByteArrayInputStream(header)))
            fail("Parser must reject a payloadLength that exceeds the cap.")
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            assertTrue(
                "Expected size-limit IOException, got '${msg}' (${e.javaClass.simpleName}).",
                msg.contains("exceeds") || msg.contains("limit") || msg.contains("too large")
            )
        }
    }

    @Test
    fun parseMessage_negativePayloadLength_throwsIOException() {
        // readInt() narrows a misaligned uint32 into a negative int. The
        // previous code happily did ByteArray(negative) which throws
        // NegativeArraySizeException — not IOException — and would not be
        // caught by the peer-loop's IOException handler.
        val header = ByteArrayOutputStream().apply {
            writeUInt32LE(magic)
            write(commandBytes("version"))
            writeInt32LE(-1)                       // negative payloadLength
            write(ByteArray(4))
        }.toByteArray()

        val parser = NetworkMessageParser(magic)
        try {
            parser.parseMessage(BitcoinInput(ByteArrayInputStream(header)))
            fail("Parser must reject negative payloadLength as IOException.")
        } catch (e: IOException) {
            // expected
        }
    }

    private fun commandBytes(cmd: String): ByteArray {
        val bytes = cmd.toByteArray()
        val buf = ByteArray(12)
        System.arraycopy(bytes, 0, buf, 0, bytes.size)
        return buf
    }

    private fun ByteArrayOutputStream.writeUInt32LE(value: Long) {
        write((value and 0xFF).toInt())
        write((value shr 8 and 0xFF).toInt())
        write((value shr 16 and 0xFF).toInt())
        write((value shr 24 and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeInt32LE(value: Int) {
        write(value and 0xFF)
        write(value shr 8 and 0xFF)
        write(value shr 16 and 0xFF)
        write(value shr 24 and 0xFF)
    }
}

package io.horizontalsystems.bitcoincore.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Bitcoin / Dash-style P2P messages declare element lengths as untrusted
 * varints. A misaligned parser (or a malicious / buggy peer) can hand us a
 * length in the hundreds of megabytes — BitcoinInput.readBytes would then
 * allocate the buffer up front and trigger an OutOfMemoryError that the
 * surrounding worker thread cannot recover from. Worker death cascades into a
 * peer reconnect, the parse fails again, and the kit ends up
 * SIGKILL'ing itself via Crashlytics.
 *
 * The safe upper bound is well above any legitimate Bitcoin-family P2P
 * message (whose hard maximum is single-digit MB). 32 MB is comfortably above
 * realistic message sizes and well below the Dalvik heap growth limit, so we
 * trade a recoverable IOException for an unrecoverable OOM.
 */
class BitcoinInputReadBytesLimitTest {

    @Test
    fun readBytes_lengthAboveMaxMessageSize_throwsIOExceptionWithoutAllocating() {
        // We do NOT prefill 32 MB+ of bytes — the test must fail-fast on the
        // declared length itself rather than try to allocate the buffer and
        // bottom out on EOFException. Match on the message to distinguish a
        // size-cap rejection from an end-of-stream condition.
        val input = BitcoinInput(ByteArrayInputStream(ByteArray(0)))

        try {
            input.readBytes(50 * 1024 * 1024) // 50 MB — above the 32 MB cap
            fail("readBytes must reject lengths exceeding the message-size cap.")
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            assertTrue(
                "Expected a size-limit IOException, got '${msg}' (${e.javaClass.simpleName}). " +
                    "Without the cap readBytes would allocate the buffer first and only " +
                    "fail later with EOFException, which masks the real defence.",
                msg.contains("exceeds") || msg.contains("too large") || msg.contains("limit")
            )
        }
    }

    @Test
    fun readBytes_negativeLength_throwsIOException() {
        // A misaligned varint can also surface as a negative int after
        // toInt() narrowing. Treat negatives the same way as oversized
        // lengths.
        val input = BitcoinInput(ByteArrayInputStream(ByteArray(0)))

        try {
            input.readBytes(-1)
            fail("readBytes must reject negative lengths.")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun readBytes_zeroLength_returnsEmptyArrayUnchanged() {
        val input = BitcoinInput(ByteArrayInputStream(ByteArray(0)))
        val result = input.readBytes(0)
        assertEquals(0, result.size)
    }

    @Test
    fun readString_lengthAboveCap_throwsIOExceptionWithoutAllocating() {
        // readString reads a varInt length and then allocates a buffer.
        // Sites like VersionMessage.subVersion and RejectMessage.reason
        // call into this — a peer can drop a huge varInt and force a
        // multi-hundred-MB allocation, bypassing the cap on readBytes.
        // Encode a 5-byte uint32 varInt (0xFE prefix) of 50 MB.
        val varIntPrefix = 0xFE.toByte()
        val len = 50 * 1024 * 1024
        val payload = byteArrayOf(
            varIntPrefix,
            (len and 0xFF).toByte(),
            (len shr 8 and 0xFF).toByte(),
            (len shr 16 and 0xFF).toByte(),
            (len shr 24 and 0xFF).toByte(),
        )
        val input = BitcoinInput(ByteArrayInputStream(payload))

        try {
            input.readString()
            fail("readString must enforce the same cap as readBytes.")
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            assertTrue(
                "Expected size-limit IOException, got '${msg}' (${e.javaClass.simpleName}).",
                msg.contains("exceeds") || msg.contains("limit") || msg.contains("too large")
            )
        }
    }

    @Test
    fun readString_lengthOverflowsIntButFitsAfterCast_throwsIOException() {
        // Catches the int-cast truncation hole: a 64-bit varint of 0x100000001
        // (4 GB + 1) narrows to (int) 1 in `(int) len`, so the cap-check in
        // readBytes would happily allocate 1 byte while the stream "promised"
        // 4 GB. No OOM, but the stream offset is silently corrupted and every
        // subsequent parser reads garbage. Reject the length BEFORE the cast.
        val payload = byteArrayOf(
            0xFF.toByte(),                     // varInt prefix: next 8 bytes are uint64 LE
            0x01, 0x00, 0x00, 0x00,            // low 32 bits = 1
            0x01, 0x00, 0x00, 0x00,            // high 32 bits = 1 → total 0x100000001 = 4 GB + 1
            0xAA.toByte()                      // one body byte the broken parser would happily return
        )
        val input = BitcoinInput(ByteArrayInputStream(payload))

        try {
            input.readString()
            fail("readString must reject a 64-bit length that exceeds the cap BEFORE narrowing to int.")
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            assertTrue(
                "Expected size-limit IOException, got '${msg}' (${e.javaClass.simpleName}).",
                msg.contains("exceeds") || msg.contains("limit") || msg.contains("too large")
            )
        }
    }

    @Test
    fun readString_smallLengthBelowCap_returnsContent() {
        val data = "hello".toByteArray()
        // varInt length 5 (single byte) + payload
        val stream = byteArrayOf(data.size.toByte()) + data
        val input = BitcoinInput(ByteArrayInputStream(stream))

        assertEquals("hello", input.readString())
    }

    @Test
    fun readBytes_smallLengthBelowCap_returnsContent() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val input = BitcoinInput(ByteArrayInputStream(payload))

        val result = input.readBytes(payload.size)

        assertEquals(5, result.size)
        for (i in payload.indices) assertEquals(payload[i], result[i])
    }
}

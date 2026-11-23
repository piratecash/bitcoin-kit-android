package io.horizontalsystems.bitcoincore.network.messages

import io.horizontalsystems.bitcoincore.crypto.BloomFilter
import io.horizontalsystems.bitcoincore.exceptions.BitcoinException
import io.horizontalsystems.bitcoincore.io.BitcoinInput
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.models.InventoryItem
import io.horizontalsystems.bitcoincore.models.NetworkAddress
import io.horizontalsystems.bitcoincore.utils.HashUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Frozen v1 wire behaviour, captured from the implementation BEFORE the BIP324 transport
 * refactor extracted the envelope into a transport seam.
 *
 * The frames below are literals on purpose. Comparing a refactored serializer against a
 * refactored parser would be self-referential and blind to a shared regression, so these
 * bytes — and the exception shapes asserted further down — are the only thing that can
 * prove v1 behaviour is unchanged for every kit.
 *
 * The command-decoding cases look like bugs and are not: they pin quirks of the existing
 * `getCommandFrom`, which the v2 path must NOT inherit and the v1 path must NOT lose.
 */
class V1FrameGoldenTest {

    private val magic = 0xD9B4BEF9L

    private fun serializer() = NetworkMessageSerializer(magic).apply {
        add(VerAckMessageSerializer())
        add(PingMessageSerializer())
        add(PongMessageSerializer())
        add(InvMessageSerializer())
        add(GetAddrMessageSerializer())
        add(MempoolMessageSerializer())
        add(VersionMessageSerializer())
        add(FilterLoadMessageSerializer())
    }

    private fun goldenAddress(lastOctet: Int) = NetworkAddress().apply {
        services = 1L
        address = ByteArray(16).also {
            it[10] = 0xFF.toByte()
            it[11] = 0xFF.toByte()
            it[12] = 192.toByte()
            it[13] = 168.toByte()
            it[15] = lastOctet.toByte()
        }
        port = 8333
    }

    private fun goldenVersionMessage() =
        VersionMessage(70015, 1L, 1_700_000_000L, goldenAddress(1)).apply {
            senderAddress = goldenAddress(2)
            nonce = this@V1FrameGoldenTest.nonce
            subVersion = "/Golden:1.0/"
            lastBlock = 800_000
            relay = false
        }

    private fun parser() = NetworkMessageParser(magic).apply {
        add(VerAckMessageParser())
        add(PingMessageParser())
        add(PongMessageParser())
        add(InvMessageParser())
    }

    private val nonce = 0x0123456789ABCDEFL
    private val hash = ByteArray(32) { (it + 1).toByte() }

    //
    // Serialization goldens
    //

    @Test
    fun serialize_verack_matchesGoldenFrame() {
        assertFrame("f9beb4d976657261636b000000000000000000005df6e0e2", VerAckMessage())
    }

    @Test
    fun serialize_ping_matchesGoldenFrame() {
        assertFrame(
            "f9beb4d970696e6700000000000000000800000033bc15e5efcdab8967452301",
            PingMessage(nonce)
        )
    }

    @Test
    fun serialize_pong_matchesGoldenFrame() {
        assertFrame(
            "f9beb4d9706f6e6700000000000000000800000033bc15e5efcdab8967452301",
            PongMessage(nonce)
        )
    }

    @Test
    fun serialize_getAddr_matchesGoldenFrame() {
        assertFrame("f9beb4d9676574616464720000000000000000005df6e0e2", GetAddrMessage())
    }

    @Test
    fun serialize_mempool_matchesGoldenFrame() {
        assertFrame("f9beb4d96d656d706f6f6c0000000000000000005df6e0e2", MempoolMessage())
    }

    @Test
    fun serialize_invSingleItem_matchesGoldenFrame() {
        assertFrame(
            "f9beb4d9696e76000000000000000000250000005428098d01010000000102030405060708090a0b0c" +
                "0d0e0f101112131415161718191a1b1c1d1e1f20",
            InvMessage(1, hash)
        )
    }

    @Test
    fun serialize_invTwoItems_matchesGoldenFrame() {
        assertFrame(
            "f9beb4d9696e76000000000000000000490000006efc16f402010000000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20020000007f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
            InvMessage(listOf(InventoryItem(1, hash), InventoryItem(2, ByteArray(32) { 0x7F })))
        )
    }

    /**
     * `version` is the first frame every peer sees, so a drift here breaks v1 fallback outright.
     * The production convenience constructor stamps a random nonce and the wall clock, so this
     * builds the message through the deterministic primary constructor instead.
     *
     * Note the trailing `01`: the serializer writes a hardcoded relay byte of 1 and ignores
     * `message.relay`. That is today's behaviour and this golden freezes it.
     */
    @Test
    fun serialize_version_matchesGoldenFrame() {
        assertFrame(
            "f9beb4d976657273696f6e0000000000620000006677d5fe7f110100010000000000000000f15365000000000100000000000000000000000000000000" +
                "00ffffc0a800018d20010000000000000000000000000000000000ffffc0a800028d20efcdab89674523010c2f476f6c64656e3a312e302f00350c0001",
            goldenVersionMessage()
        )
    }

    @Test
    fun parseMessage_goldenVersionFrame_roundTripsAllFields() {
        val parser = NetworkMessageParser(magic).apply { add(VersionMessageParser()) }
        val frame = serializer().serialize(goldenVersionMessage())

        val message = parser.parseMessage(BitcoinInput(ByteArrayInputStream(frame)))

        assertTrue(message is VersionMessage)
        message as VersionMessage
        assertEquals(70015, message.protocolVersion)
        assertEquals(1L, message.services)
        assertEquals(1_700_000_000L, message.timestamp)
        assertEquals(0x0123456789ABCDEFL, message.nonce)
        assertEquals("/Golden:1.0/", message.subVersion)
        assertEquals(800_000, message.lastBlock)
        assertTrue("serializer hardcodes relay=1, so the parsed value is true", message.relay)
    }

    /**
     * `filterload` cannot have a literal golden frame: [io.horizontalsystems.bitcoincore.crypto.BloomFilter]
     * seeds itself from `Math.random()` with no injection seam, and that tweak feeds the filter
     * bytes themselves. Pinning the envelope is still worthwhile, because the envelope is exactly
     * what the transport refactor moves.
     */
    @Test
    fun serialize_filterLoad_envelopeIsWellFormed() {
        val frame = serializer().serialize(FilterLoadMessage(BloomFilter(listOf(hash))))

        assertArrayEquals("magic", "f9beb4d9".decodeHex(), frame.copyOfRange(0, 4))
        assertArrayEquals("command", commandBytes("filterload"), frame.copyOfRange(4, 16))

        val payload = frame.copyOfRange(24, frame.size)
        val declaredLength = frame.copyOfRange(16, 20).let {
            (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) or
                ((it[2].toInt() and 0xFF) shl 16) or ((it[3].toInt() and 0xFF) shl 24)
        }
        assertEquals("declared payload length", payload.size, declaredLength)
        assertArrayEquals(
            "checksum",
            HashUtils.doubleSha256(payload).copyOfRange(0, 4),
            frame.copyOfRange(20, 24)
        )
    }

    @Test
    fun serialize_unregisteredMessage_throwsNoSerializer() {
        try {
            serializer().serialize(UnknownMessage("whatever"))
            fail("Serializing a message with no registered serializer must throw.")
        } catch (e: NoSerializer) {
            // expected
        }
    }

    //
    // Parsing goldens
    //

    @Test
    fun parseMessage_goldenPingFrame_roundTrips() {
        val message = parser().parseMessage(input("f9beb4d970696e6700000000000000000800000033bc15e5efcdab8967452301"))

        assertTrue(message is PingMessage)
        assertEquals(nonce, (message as PingMessage).nonce)
    }

    @Test
    fun parseMessage_wrongMagic_throwsBitcoinException() {
        try {
            parser().parseMessage(input("0b110907" + "76657261636b000000000000" + "00000000" + "5df6e0e2"))
            fail("A frame with foreign magic must be rejected.")
        } catch (e: BitcoinException) {
            assertTrue(e.message.orEmpty().contains("Bad magic"))
        }
    }

    @Test
    fun parseMessage_badChecksum_throwsBitcoinException() {
        try {
            parser().parseMessage(input("f9beb4d970696e6700000000000000000800000000000000efcdab8967452301"))
            fail("A frame with a wrong checksum must be rejected.")
        } catch (e: BitcoinException) {
            assertTrue(e.message.orEmpty().contains("Checksum"))
        }
    }

    @Test
    fun parseMessage_unregisteredCommand_returnsUnknownMessage() {
        val message = parser().parseMessage(BitcoinInput(ByteArrayInputStream(frame("notacommand", ByteArray(0)))))

        assertTrue(message is UnknownMessage)
        assertEquals("notacommand", (message as UnknownMessage).command)
    }

    /**
     * Quirk, deliberately frozen: the trailing-NUL scan stops at index 0 and the `n <= 0`
     * guard then rejects the frame, so a legitimate one-character command is unparseable.
     * BIP324's strict decoder must not copy this; v1 must not lose it.
     */
    @Test
    fun parseMessage_singleCharacterCommand_throwsBadCommandBytes() {
        try {
            parser().parseMessage(BitcoinInput(ByteArrayInputStream(frame("a", ByteArray(0)))))
            fail("Current v1 behaviour rejects a one-character command.")
        } catch (e: BitcoinException) {
            assertTrue(e.message.orEmpty().contains("Bad command bytes"))
        }
    }

    /**
     * Quirk, deliberately frozen: only trailing NULs are stripped, so an embedded NUL
     * survives into the command string instead of terminating it.
     */
    @Test
    fun parseMessage_commandWithEmbeddedNul_keepsNulInCommandString() {
        val commandBytes = ByteArray(12)
        "ab".toByteArray().copyInto(commandBytes, 0)
        "cd".toByteArray().copyInto(commandBytes, 3)

        val message = parser().parseMessage(BitcoinInput(ByteArrayInputStream(frame(commandBytes, ByteArray(0)))))

        assertTrue(message is UnknownMessage)
        assertEquals("ab\u0000cd", (message as UnknownMessage).command)
    }

    /**
     * Quirk, deliberately frozen: non-printable command bytes are accepted as-is.
     */
    @Test
    fun parseMessage_nonPrintableCommandBytes_areAccepted() {
        val commandBytes = ByteArray(12)
        commandBytes[0] = 0x01
        commandBytes[1] = 0x02

        val message = parser().parseMessage(BitcoinInput(ByteArrayInputStream(frame(commandBytes, ByteArray(0)))))

        assertTrue(message is UnknownMessage)
        assertEquals("\u0001\u0002", (message as UnknownMessage).command)
    }

    /**
     * Exception-shape golden: a registered parser that blows up on a malformed payload is
     * wrapped in RuntimeException with the original exception as its cause. The v2 path
     * translates such failures into a recoverable transport-level exception instead, so
     * this assertion is what keeps the two paths from drifting into each other.
     */
    @Test
    fun parseMessage_registeredParserThrows_wrapsInRuntimeExceptionPreservingTheOriginal() {
        // A sentinel instance, so the assertion proves the ORIGINAL exception is propagated as the
        // cause rather than merely that some cause exists — the latter would still pass if the
        // refactor replaced it with an unrelated wrapper.
        val sentinel = IllegalStateException("sentinel")
        val throwingParser = object : IMessageParser {
            override val command = "boom"
            override fun parseMessage(input: BitcoinInputMarkable): IMessage = throw sentinel
        }
        val parser = NetworkMessageParser(magic).apply { add(throwingParser) }

        try {
            parser.parseMessage(BitcoinInput(ByteArrayInputStream(frame("boom", ByteArray(0)))))
            fail("A parser failure must surface.")
        } catch (e: RuntimeException) {
            assertEquals(RuntimeException::class.java, e.javaClass)
            assertSame("The original parser exception must be the cause.", sentinel, e.cause)
        }
    }

    @Test
    fun parseMessage_truncatedPayload_surfacesAsRuntimeException() {
        // "ping" declares an 8-byte nonce; give it 2 bytes so PingMessageParser hits EOF.
        val truncated = frame("ping", byteArrayOf(0x01, 0x02))

        try {
            parser().parseMessage(BitcoinInput(ByteArrayInputStream(truncated)))
            fail("A truncated payload must surface.")
        } catch (e: RuntimeException) {
            assertEquals(RuntimeException::class.java, e.javaClass)
        }
    }

    @Test
    fun parseMessage_payloadLengthAboveCap_throwsIOException() {
        val header = ByteArrayOutputStream().apply {
            writeUInt32LE(magic)
            write(commandBytes("version"))
            writeInt32LE(50 * 1024 * 1024)
            write(ByteArray(4))
        }.toByteArray()

        try {
            parser().parseMessage(BitcoinInput(ByteArrayInputStream(header)))
            fail("An oversized payloadLength must be rejected before allocating.")
        } catch (e: IOException) {
            // expected
        }
    }

    //
    // helpers
    //

    private fun assertFrame(expectedHex: String, message: IMessage) {
        assertArrayEquals(
            "v1 frame changed for $message — this is the D4 regression gate, not a test to update lightly.",
            expectedHex.decodeHex(),
            serializer().serialize(message)
        )
    }

    private fun input(hex: String) = BitcoinInput(ByteArrayInputStream(hex.decodeHex()))

    private fun frame(command: String, payload: ByteArray) = frame(commandBytes(command), payload)

    private fun frame(command: ByteArray, payload: ByteArray) = ByteArrayOutputStream().apply {
        writeUInt32LE(magic)
        write(command)
        writeInt32LE(payload.size)
        write(HashUtils.doubleSha256(payload).copyOfRange(0, 4))
        write(payload)
    }.toByteArray()

    private fun commandBytes(command: String): ByteArray {
        val buffer = ByteArray(12)
        command.toByteArray().copyInto(buffer)
        return buffer
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

    private fun String.decodeHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

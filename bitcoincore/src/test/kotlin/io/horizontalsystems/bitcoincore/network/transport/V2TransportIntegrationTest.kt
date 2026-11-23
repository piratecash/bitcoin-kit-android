package io.horizontalsystems.bitcoincore.network.transport

import io.horizontalsystems.bitcoincore.io.BitcoinInput
import io.horizontalsystems.bitcoincore.network.messages.MessageCommand
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import io.horizontalsystems.bitcoincore.network.messages.PingMessage
import io.horizontalsystems.bitcoincore.network.messages.PingMessageParser
import io.horizontalsystems.bitcoincore.network.messages.PingMessageSerializer
import io.horizontalsystems.bitcoincore.network.messages.UnknownMessage
import io.horizontalsystems.bitcoincore.network.transport.v2.Bip324Cipher
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.EllSwift
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.IEntropySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Random

/**
 * Drives the real [V2Transport] against a reference responder built from the same primitives with
 * the roles swapped.
 *
 * This is not self-referential at the cipher level: key derivation and packet encryption are pinned
 * independently by the official BIP324 vectors in `Bip324CipherTest`. What these tests add is the
 * framing the vectors do not cover — fragmented reads, the garbage-terminator scan, AAD boundaries,
 * decoys, and error classification.
 */
class V2TransportIntegrationTest {

    private val magicBytes = byteArrayOf(0x70, 0x75, 0x6D, 0x70) // PirateCash

    private class ScriptedEntropy(seed: Long, private val garbageLength: Int) : IEntropySource {
        private val random = Random(seed)
        override fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
        override fun nextInt(boundExclusive: Int) = garbageLength
    }

    /** Feeds bytes to the transport, optionally one at a time, to exercise fragmented reads. */
    private class ChunkedStream(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var position = 0
        override fun read(): Int = if (position >= data.size) -1 else data[position++].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= data.size) return -1
            val count = minOf(chunk, len, data.size - position)
            System.arraycopy(data, position, b, off, count)
            position += count
            return count
        }
        override fun available(): Int = data.size - position
    }

    /** Reads exactly what it is given; the deadline is irrelevant for these framing tests. */
    private class StreamDeadlineReader(private val input: InputStream) : IDeadlineReader {
        override fun readFully(n: Int): ByteArray {
            val buffer = ByteArray(n)
            var offset = 0
            while (offset < n) {
                val read = input.read(buffer, offset, n - offset)
                if (read < 0) throw java.io.EOFException("stream exhausted")
                offset += read
            }
            return buffer
        }

        override fun readByte(): Byte {
            val value = input.read()
            if (value < 0) throw java.io.EOFException("stream exhausted")
            return value.toByte()
        }
    }

    /**
     * The peer side of a handshake: derives the mirrored session and produces exactly the bytes a
     * BIP324 responder would send.
     */
    private inner class ReferenceResponder(garbageLength: Int, private val decoysBeforeVersion: Int = 0) {
        private val entropy = ScriptedEntropy(seed = 99, garbageLength = garbageLength)
        private val priv = entropy.scalar()
        val ellswift: ByteArray = EllSwift.ellswiftCreate(priv, entropy)
        private val garbage = entropy.bytes(garbageLength)
        private var cipher: Bip324Cipher? = null

        fun handshakeBytes(initiatorEllswift: ByteArray): ByteArray {
            val session = Bip324Cipher.create(priv, ellswift, initiatorEllswift, initiating = false, magicBytes = magicBytes)
            cipher = session

            val out = ByteArrayOutputStream()
            out.write(ellswift)
            out.write(garbage)
            out.write(session.sendGarbageTerminator)
            var aad = garbage
            repeat(decoysBeforeVersion) {
                out.write(session.encrypt(byteArrayOf(0x11, 0x22), aad, ignore = true))
                aad = ByteArray(0)
            }
            out.write(session.encrypt(ByteArray(0), aad, ignore = false))
            return out.toByteArray()
        }

        /** An application packet, as the peer would send it after the handshake. */
        fun packet(command: String, payload: ByteArray, ignore: Boolean = false): ByteArray {
            val session = checkNotNull(cipher)
            val contents = ByteArray(1 + MessageCommand.COMMAND_SIZE + payload.size)
            MessageCommand.encode(command).copyInto(contents, 1)
            payload.copyInto(contents, 1 + MessageCommand.COMMAND_SIZE)
            return session.encrypt(contents, ByteArray(0), ignore)
        }

        fun shortIdPacket(shortId: Int, payload: ByteArray): ByteArray {
            val session = checkNotNull(cipher)
            val contents = ByteArray(1 + payload.size)
            contents[0] = shortId.toByte()
            payload.copyInto(contents, 1)
            return session.encrypt(contents, ByteArray(0), ignore = false)
        }
    }

    private fun transport(entropy: IEntropySource, usesDashShortIds: Boolean = true) = V2Transport(
        magicBytes = magicBytes,
        usesDashShortIds = usesDashShortIds,
        maxContentsLength = 1 + 12 + 3 * 1024 * 1024,
        parser = NetworkMessageParser(0).apply { add(PingMessageParser()) },
        serializer = NetworkMessageSerializer(0).apply { add(PingMessageSerializer()) },
        entropy = entropy,
    )

    /**
     * Lazily produces the peer's bytes on first read, once the initiator has already written its
     * ElligatorSwift key. Replaying the initiator's entropy to guess that key would be fragile and
     * would not prove the two sides agree; this reads what was actually sent.
     */
    private class LazyPeerStream(private val supply: () -> ByteArray) : InputStream() {
        private var delegate: ChunkedStream? = null
        private fun stream(): ChunkedStream = delegate ?: ChunkedStream(supply(), chunkSize).also { delegate = it }
        var chunkSize: Int = Int.MAX_VALUE
        override fun read(): Int = stream().read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = stream().read(b, off, len)
        override fun available(): Int = stream().available()
    }

    /**
     * @param garbageLength both sides' garbage length; 0 and 4095 are the interesting boundaries.
     * @param chunk how many bytes the peer's stream yields per read, to force fragmentation.
     */
    private fun connect(
        garbageLength: Int = 8,
        chunk: Int = Int.MAX_VALUE,
        decoys: Int = 0,
        extra: (ReferenceResponder) -> ByteArray = { ByteArray(0) },
    ): Triple<V2Transport, BitcoinInput, ByteArrayOutputStream> {
        val transport = transport(ScriptedEntropy(seed = 7, garbageLength = garbageLength))
        val responder = ReferenceResponder(garbageLength, decoys)
        val output = ByteArrayOutputStream()

        val stream = LazyPeerStream {
            // By now the initiator has written exactly `ellswift || garbage`.
            val initiatorEllswift = output.toByteArray().copyOfRange(0, 64)
            responder.handshakeBytes(initiatorEllswift) + extra(responder)
        }.apply { chunkSize = chunk }

        transport.connect(StreamDeadlineReader(stream), output)
        return Triple(transport, BitcoinInput(stream), output)
    }

    @Test
    fun connect_thenExchangeMessages_roundTrips() {
        val responderHolder = arrayOfNulls<ReferenceResponder>(1)
        val (transport, input, output) = connect(extra = { responder ->
            responderHolder[0] = responder
            responder.packet("ping", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        })

        assertTrue("session must be established", transport.isEncrypted)
        assertTrue("handshake must have written key, garbage, terminator and version packet", output.size() > 64)

        val message = transport.readMessage(input)
        assertTrue(message is PingMessage)
    }

    @Test
    fun connect_withNoGarbage_succeeds() {
        val (transport, _, _) = connect(garbageLength = 0)
        assertTrue(transport.isEncrypted)
    }

    @Test
    fun connect_withMaximumGarbage_succeeds() {
        val (transport, _, _) = connect(garbageLength = 4095)
        assertTrue(transport.isEncrypted)
    }

    @Test
    fun connect_withByteAtATimeStream_succeeds() {
        val (transport, _, _) = connect(chunk = 1)
        assertTrue("fragmented reads must not break the terminator scan", transport.isEncrypted)
    }

    @Test
    fun connect_withDecoysBeforeVersionPacket_succeeds() {
        val (transport, _, _) = connect(decoys = 3)
        assertTrue("decoys during the handshake must be skipped", transport.isEncrypted)
    }

    @Test
    fun readMessage_decoyPacket_returnsNullWithoutBreakingTheStream() {
        val (transport, input, _) = connect(extra = { responder ->
            responder.packet("ping", ByteArray(8), ignore = true) +
                responder.packet("ping", byteArrayOf(9, 0, 0, 0, 0, 0, 0, 0))
        })

        assertNull("a decoy carries no message", transport.readMessage(input))
        assertTrue("the stream stays usable afterwards", transport.readMessage(input) is PingMessage)
    }

    @Test
    fun readMessage_shortIdInDashNamespace_isDecoded() {
        val (transport, input, _) = connect(extra = { responder ->
            // 144 = mnlistdiff; no parser is registered, so it surfaces as UnknownMessage carrying
            // the decoded command rather than the raw id.
            responder.shortIdPacket(144, byteArrayOf(1, 2, 3))
        })

        val message = transport.readMessage(input)
        assertEquals("mnlistdiff", (message as UnknownMessage).command)
    }

    @Test
    fun readMessage_unknownShortId_surfacesAsUnknownMessage() {
        val (transport, input, _) = connect(extra = { responder -> responder.shortIdPacket(200, ByteArray(0)) })

        val message = transport.readMessage(input)
        assertEquals("v2:200", (message as UnknownMessage).command)
    }

    @Test
    fun readMessage_tamperedPacket_throwsAuthenticationFailed() {
        val (transport, input, _) = connect(extra = { responder ->
            responder.packet("ping", ByteArray(8)).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        })

        try {
            transport.readMessage(input)
            fail("A tampered packet must not be accepted")
        } catch (e: TransportException.AuthenticationFailed) {
            // expected: fatal, the caller closes the connection
        }
    }

    @Test
    fun connect_peerClosesImmediately_reportsHandshakeFailed() {
        val transport = transport(ScriptedEntropy(seed = 7, garbageLength = 8))

        try {
            transport.connect(StreamDeadlineReader(ByteArrayInputStream(ByteArray(0))), ByteArrayOutputStream())
            fail("An immediate EOF must be reported")
        } catch (e: TransportException.HandshakeFailed) {
            // A legacy peer hanging up is the single most common real case, and it MUST be
            // classified as a handshake failure so PeerGroup falls back instead of deleting the host.
        }
    }

    @Test
    fun connect_peerNeverSendsTerminator_reportsHandshakeFailed() {
        val transport = transport(ScriptedEntropy(seed = 7, garbageLength = 8))
        // A valid-looking key followed by endless non-terminator bytes.
        val noise = ByteArray(64 + 4096 + 32) { 0x5A }

        try {
            transport.connect(StreamDeadlineReader(ByteArrayInputStream(noise)), ByteArrayOutputStream())
            fail("The garbage cap must be enforced")
        } catch (e: TransportException.HandshakeFailed) {
            // expected
        }
    }

    @Test
    fun writeMessage_beforeConnect_throwsStreamClosed() {
        val transport = transport(ScriptedEntropy(seed = 7, garbageLength = 8))

        try {
            transport.writeMessage(PingMessage(1), ByteArrayOutputStream())
            fail("Writing before the handshake must not silently succeed")
        } catch (e: TransportException.StreamClosed) {
            // expected
        }
    }

    /**
     * A stall part-way through a packet is fatal, not something to retry.
     *
     * The length cipher has already advanced by the time the body is read, so resuming would treat
     * the rest of this packet as the next packet's length and desynchronize the stream for good.
     * Before this was classified, the IOException reached the receive loop's legacy catch and was
     * silently swallowed — the connection stayed "open" over a stream that could never recover.
     */
    @Test
    fun readMessage_truncatedMidPacket_isFatal() {
        val (transport, input, _) = connect(extra = { responder ->
            // A complete length field, then only part of the body.
            responder.packet("ping", ByteArray(8)).copyOfRange(0, Bip324Cipher.LENGTH_LEN + 4)
        })

        try {
            transport.readMessage(input)
            fail("A truncated packet must terminate the stream")
        } catch (e: TransportException) {
            // expected: fatal, so PeerConnection closes instead of resyncing on garbage
        }
    }

    @Test
    fun readMessage_truncatedMidLengthField_isFatal() {
        val (transport, input, _) = connect(extra = { byteArrayOf(0x01, 0x02) })

        try {
            transport.readMessage(input)
            fail("A partial length field must terminate the stream")
        } catch (e: TransportException) {
            // expected
        }
    }

    @Test
    fun readMessage_lengthAboveTheNetworkCap_isRejectedBeforeAllocating() {
        val small = V2Transport(
            magicBytes = magicBytes,
            usesDashShortIds = true,
            maxContentsLength = 16,
            parser = NetworkMessageParser(0).apply { add(PingMessageParser()) },
            serializer = NetworkMessageSerializer(0).apply { add(PingMessageSerializer()) },
            entropy = ScriptedEntropy(seed = 7, garbageLength = 8),
        )
        val responder = ReferenceResponder(8, 0)
        val output = ByteArrayOutputStream()
        val stream = LazyPeerStream {
            responder.handshakeBytes(output.toByteArray().copyOfRange(0, 64)) +
                responder.packet("ping", ByteArray(64))
        }
        small.connect(StreamDeadlineReader(stream), output)

        try {
            small.readMessage(BitcoinInput(stream))
            fail("A packet above the per-network cap must be rejected")
        } catch (e: TransportException.MalformedMessage) {
            assertTrue(e.message.orEmpty().contains("too large"))
        }
    }

    /** A malformed payload is recoverable: the packet was authenticated, so the stream survives. */
    @Test
    fun readMessage_authenticatedPacketWithBadPayload_isRecoverable() {
        val (transport, input, _) = connect(extra = { responder ->
            // "ping" wants an 8-byte nonce; give it 2 so the parser throws.
            responder.packet("ping", byteArrayOf(1, 2)) +
                responder.packet("ping", ByteArray(8))
        })

        try {
            transport.readMessage(input)
            fail("The payload parser must surface its failure")
        } catch (e: MessagePayloadException) {
            // Recoverable by design.
        }
        assertTrue("the stream must survive a bad payload", transport.readMessage(input) is PingMessage)
    }

    @Test
    fun close_isIdempotent() {
        val (transport, _, _) = connect()

        transport.close()
        transport.close()

        assertTrue("close must not leave a live session", !transport.isEncrypted)
    }
}

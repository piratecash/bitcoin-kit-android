package io.horizontalsystems.bitcoincore.network.transport.v2

import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.EllSwift
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.IEntropySource
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.TaggedHash
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.hexToBytes
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Driven by the official BIP324 vectors (`bip-0324/packet_encoding_test_vectors.csv`), following
 * the same procedure as Bitcoin Core's own `src/test/bip324_tests.cpp`: seek to the numbered packet
 * by encrypting `in_idx` empty decoys, then encrypt the real one.
 *
 * Rows 4-7 sit at packet indices 223, 448, 673 and 1024 — the rekey boundaries — which is what
 * makes this file the boundary test for the 224-packet key rotation. A rekey bug is invisible in a
 * short session and would only surface days into a real sync.
 */
class Bip324CipherTest {

    // Mainnet magic, the value the vectors were generated with.
    private val magicBytes = byteArrayOf(0xF9.toByte(), 0xBE.toByte(), 0xB4.toByte(), 0xD9.toByte())

    private class Vector(row: List<String>) {
        val idx = row[0].toInt()
        val priv = row[1].hexToBytes()
        val ellswiftOurs = row[2].hexToBytes()
        val ellswiftTheirs = row[3].hexToBytes()
        val initiating = row[4] == "1"
        val contents = row[5].hexToBytes()
        val multiply = row[6].toInt()
        val aad = row[7].hexToBytes()
        val ignore = row[8] == "1"
        val xShared = row[11]
        val sharedSecret = row[12]
        val sendGarbageTerminator = row[17].hexToBytes()
        val recvGarbageTerminator = row[18].hexToBytes()
        val sessionId = row[19].hexToBytes()
        val ciphertext = row[20]
        val ciphertextEndsWith = row[21]
    }

    private fun vectors(): List<Vector> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/bip324/packet_encoding_test_vectors.csv")) {
            "missing packet_encoding_test_vectors.csv"
        }
        return stream.bufferedReader().useLines { lines ->
            lines.drop(1).filter { it.isNotBlank() }.map { Vector(it.split(",")) }.toList()
        }
    }

    @Test
    fun packetVectors_matchOfficialBip324Vectors() {
        val vectors = vectors()
        // Guards against a vacuous pass if the resource ever goes missing.
        assertEquals("official packet vectors", 7, vectors.size)

        for (vector in vectors) {
            val cipher = Bip324Cipher.create(
                priv = vector.priv,
                ellswiftOurs = vector.ellswiftOurs,
                ellswiftTheirs = vector.ellswiftTheirs,
                initiating = vector.initiating,
                magicBytes = magicBytes,
            )

            assertArrayEquals("session id (idx=${vector.idx})", vector.sessionId, cipher.sessionId)
            assertArrayEquals("send terminator (idx=${vector.idx})", vector.sendGarbageTerminator, cipher.sendGarbageTerminator)
            assertArrayEquals("recv terminator (idx=${vector.idx})", vector.recvGarbageTerminator, cipher.recvGarbageTerminator)

            // Seek to the numbered packet exactly as the reference does: empty decoy packets.
            repeat(vector.idx) { cipher.encrypt(ByteArray(0), ByteArray(0), ignore = true) }

            val contents = ByteArray(vector.contents.size * vector.multiply)
            repeat(vector.multiply) { vector.contents.copyInto(contents, it * vector.contents.size) }
            val ciphertext = cipher.encrypt(contents, vector.aad, vector.ignore)

            if (vector.ciphertext.isNotEmpty()) {
                assertEquals("ciphertext (idx=${vector.idx})", vector.ciphertext, ciphertext.toHex())
            } else {
                val suffix = vector.ciphertextEndsWith.hexToBytes()
                assertTrue("ciphertext shorter than expected suffix", ciphertext.size >= suffix.size)
                assertArrayEquals(
                    "ciphertext suffix (idx=${vector.idx})",
                    suffix,
                    ciphertext.copyOfRange(ciphertext.size - suffix.size, ciphertext.size),
                )
            }
        }
    }

    /**
     * Pins the two derivation steps before HKDF separately, so a mistake in the ECDH transcript
     * ordering is reported where it happens rather than as an opaque ciphertext mismatch far
     * downstream. The role decides whose encoding is hashed first.
     */
    @Test
    fun sharedSecret_matchesVectorIntermediates() {
        for (vector in vectors()) {
            val x = EllSwift.ellswiftEcdhXonly(vector.ellswiftTheirs, vector.priv)
            assertEquals("mid_x_shared (idx=${vector.idx})", vector.xShared, x.toHex())

            val transcript = if (vector.initiating) {
                vector.ellswiftOurs + vector.ellswiftTheirs + x
            } else {
                vector.ellswiftTheirs + vector.ellswiftOurs + x
            }
            val secret = TaggedHash.hash("bip324_ellswift_xonly_ecdh", transcript)

            assertEquals("mid_shared_secret (idx=${vector.idx})", vector.sharedSecret, secret.toHex())
        }
    }

    /**
     * A real session between two peers: each side has its own key pair, exchanges ElligatorSwift
     * encodings, and takes the opposite role. Both must derive the same secret, so what the
     * initiator writes on its send side the responder reads on its receive side.
     *
     * Note the two sides must NOT be built from one key pair with the role flipped — the transcript
     * is ordered initiator-first, so that would derive two unrelated sessions.
     */
    private class Pair {
        val entropy = object : IEntropySource {
            private val random = java.util.Random(42)
            override fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
        }
        val privA: ByteArray = entropy.scalar()
        val privB: ByteArray = entropy.scalar()
        val ellswiftA: ByteArray = EllSwift.ellswiftCreate(privA, entropy)
        val ellswiftB: ByteArray = EllSwift.ellswiftCreate(privB, entropy)

        fun initiator(magic: ByteArray) = Bip324Cipher.create(privA, ellswiftA, ellswiftB, true, magic)
        fun responder(magic: ByteArray) = Bip324Cipher.create(privB, ellswiftB, ellswiftA, false, magic)
    }

    @Test
    fun encryptThenDecrypt_betweenTwoPeers_roundTrips() {
        val pair = Pair()
        val initiator = pair.initiator(magicBytes)
        val responder = pair.responder(magicBytes)

        assertArrayEquals("both peers must derive the same session", initiator.sessionId, responder.sessionId)
        assertArrayEquals(initiator.sendGarbageTerminator, responder.recvGarbageTerminator)

        val contents = "hello bip324".toByteArray()
        val aad = byteArrayOf(1, 2, 3)
        val packet = initiator.encrypt(contents, aad, ignore = false)

        val length = responder.decryptLength(packet.copyOfRange(0, Bip324Cipher.LENGTH_LEN))
        assertEquals(contents.size, length)

        val decrypted = checkNotNull(responder.decrypt(packet.copyOfRange(Bip324Cipher.LENGTH_LEN, packet.size), aad))
        assertArrayEquals(contents, decrypted.contents)
        assertEquals(false, decrypted.ignore)
    }

    @Test
    fun decrypt_ignoreBitSet_isReportedAsDecoy() {
        val pair = Pair()
        val initiator = pair.initiator(magicBytes)
        val responder = pair.responder(magicBytes)

        val packet = initiator.encrypt(byteArrayOf(9, 9), ByteArray(0), ignore = true)
        responder.decryptLength(packet.copyOfRange(0, Bip324Cipher.LENGTH_LEN))

        val decrypted = checkNotNull(responder.decrypt(packet.copyOfRange(Bip324Cipher.LENGTH_LEN, packet.size), ByteArray(0)))
        assertTrue("decoy packets must be reported so the caller can skip them", decrypted.ignore)
    }

    @Test
    fun decrypt_tamperedTag_returnsNull() {
        val pair = Pair()
        val initiator = pair.initiator(magicBytes)
        val responder = pair.responder(magicBytes)

        val packet = initiator.encrypt(byteArrayOf(7), ByteArray(0), ignore = false)
        responder.decryptLength(packet.copyOfRange(0, Bip324Cipher.LENGTH_LEN))
        val body = packet.copyOfRange(Bip324Cipher.LENGTH_LEN, packet.size)
        body[body.size - 1] = (body[body.size - 1].toInt() xor 0x01).toByte()

        assertNull(responder.decrypt(body, ByteArray(0)))
    }

    /**
     * Bitcoin Core's vector test decrypts as well as encrypts. Encryption alone never exercises the
     * initiator's OWN receive keys, so a swapped `recvLength`/`recvPacket` assignment would stay
     * green. This drives traffic responder -> initiator and pushes it past a rekey boundary, since
     * the receive side rotates its key on the same 224-packet schedule.
     */
    @Test
    fun decrypt_responderToInitiator_acrossRekeyBoundary_roundTrips() {
        val pair = Pair()
        val initiator = pair.initiator(magicBytes)
        val responder = pair.responder(magicBytes)

        // 224 packets is one full epoch, so the 225th is encrypted under a rotated key.
        repeat(225) { index ->
            val contents = byteArrayOf(index.toByte(), (index shr 8).toByte())
            val packet = responder.encrypt(contents, ByteArray(0), ignore = false)

            val length = initiator.decryptLength(packet.copyOfRange(0, Bip324Cipher.LENGTH_LEN))
            assertEquals("length at packet $index", contents.size, length)

            val decrypted = checkNotNull(initiator.decrypt(packet.copyOfRange(Bip324Cipher.LENGTH_LEN, packet.size), ByteArray(0))) {
                "packet $index failed to authenticate"
            }
            assertArrayEquals("contents at packet $index", contents, decrypted.contents)
        }
    }

    /**
     * The three-byte length field is encrypted separately from the payload, so its boundaries need
     * their own coverage — the official vectors only assert a ciphertext suffix, which excludes it.
     */
    @Test
    fun decryptLength_atFieldBoundaries_roundTrips() {
        val pair = Pair()
        val initiator = pair.initiator(magicBytes)
        val responder = pair.responder(magicBytes)

        // Encrypting 16 MB payloads here would dominate the suite's runtime, so the length field is
        // exercised directly: it is an independent stream cipher over exactly three bytes.
        listOf(0, 1, 0xFFFFFE, 0xFFFFFF).forEach { length ->
            val encoded = byteArrayOf(
                (length and 0xFF).toByte(),
                ((length shr 8) and 0xFF).toByte(),
                ((length shr 16) and 0xFF).toByte(),
            )
            val encrypted = initiator.encryptLengthForTest(encoded)

            assertEquals("length $length", length, responder.decryptLength(encrypted))
        }
    }

    /** A wrong network magic must not merely change the ciphertext — it must change the whole session. */
    @Test
    fun create_differentNetworkMagic_derivesDifferentSession() {
        val vector = vectors().first()
        val mainnet = Bip324Cipher.create(vector.priv, vector.ellswiftOurs, vector.ellswiftTheirs, true, magicBytes)
        val pirateCash = Bip324Cipher.create(
            vector.priv, vector.ellswiftOurs, vector.ellswiftTheirs, true,
            byteArrayOf(0x70, 0x75, 0x6D, 0x70),
        )

        assertTrue(!mainnet.sessionId.contentEquals(pirateCash.sessionId))
    }
}

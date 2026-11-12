package io.horizontalsystems.bitcoincore.network.transport.v2

import io.horizontalsystems.bitcoincore.network.transport.IDeadlineReader
import io.horizontalsystems.bitcoincore.network.transport.TransportException
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.EllSwift
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.IEntropySource
import java.io.OutputStream

/** Outcome of a completed handshake: the live cipher plus the garbage that authenticated it. */
internal class HandshakeResult(val cipher: Bip324Cipher, val receivedGarbage: ByteArray)

/**
 * BIP324 handshake, initiator side only (plan §2.2).
 *
 * A wallet never accepts inbound connections, so the responder half — including its
 * "sniff the first 16 bytes for a v1 prefix" logic — is deliberately absent.
 */
internal class V2Handshake(
    private val magicBytes: ByteArray,
    private val entropy: IEntropySource,
) {

    fun perform(reader: IDeadlineReader, output: OutputStream): HandshakeResult {
        // Scalar generation is inside the try on purpose: a failing entropy provider must still be
        // reported as a handshake failure, otherwise the raw exception escapes classification and
        // PeerGroup deletes the peer address instead of retrying the peer over v1.
        var priv: ByteArray? = null
        var cipher: Bip324Cipher? = null
        try {
            val privateKey = entropy.scalar()
            priv = privateKey
            val ellswiftOurs = EllSwift.ellswiftCreate(privateKey, entropy)
            val garbage = entropy.bytes(entropy.nextInt(MAX_GARBAGE_LEN + 1))

            // Key and garbage go out immediately: to an observer the connection opens with bytes
            // indistinguishable from noise, which is the entire point of the ElligatorSwift encoding.
            output.write(ellswiftOurs + garbage)
            output.flush()

            val ellswiftTheirs = reader.readFully(ELLSWIFT_LEN)
            val session = Bip324Cipher.create(privateKey, ellswiftOurs, ellswiftTheirs, initiating = true, magicBytes = magicBytes)
            cipher = session

            // Our garbage authenticates our first packet; the terminator itself is not part of it.
            output.write(session.sendGarbageTerminator + session.encrypt(ByteArray(0), garbage))
            output.flush()
            garbage.fill(0)

            val receivedGarbage = readUntilGarbageTerminator(reader, session.recvGarbageTerminator)
            consumeVersionPacket(reader, session, receivedGarbage)

            return HandshakeResult(session, receivedGarbage)
        } catch (e: TransportException.HandshakeFailed) {
            cipher?.wipe()
            throw e
        } catch (e: Exception) {
            cipher?.wipe()
            // Blanket classification (plan §2.2.2): everything that goes wrong before the handshake
            // completes is a handshake failure, so PeerGroup downgrades the peer to v1 instead of
            // deleting its address.
            throw TransportException.HandshakeFailed("BIP324 handshake failed: ${e.message}", e)
        } finally {
            priv?.fill(0)
        }
    }

    /**
     * Scans the peer's stream one byte at a time until the trailing 16 bytes equal the expected
     * terminator, and returns the garbage that preceded it — terminator excluded, because that is
     * what BIP324 authenticates as AAD.
     */
    private fun readUntilGarbageTerminator(reader: IDeadlineReader, terminator: ByteArray): ByteArray {
        val received = ArrayList<Byte>(Bip324Cipher.GARBAGE_TERMINATOR_LEN)
        val limit = MAX_GARBAGE_LEN + Bip324Cipher.GARBAGE_TERMINATOR_LEN
        while (received.size < limit) {
            received.add(reader.readByte())
            if (received.size < Bip324Cipher.GARBAGE_TERMINATOR_LEN) continue

            val tailStart = received.size - Bip324Cipher.GARBAGE_TERMINATOR_LEN
            var matches = true
            for (index in terminator.indices) {
                if (received[tailStart + index] != terminator[index]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                return ByteArray(tailStart) { received[it] }
            }
        }
        throw TransportException.HandshakeFailed("No garbage terminator within $limit bytes")
    }

    /**
     * Reads packets until the first non-decoy one, which is the peer's version packet. Its contents
     * are ignored — the field exists for future negotiation and is currently empty — but decoys
     * before it must still be decrypted so both ciphers stay in step.
     */
    private fun consumeVersionPacket(reader: IDeadlineReader, cipher: Bip324Cipher, receivedGarbage: ByteArray) {
        var aad = receivedGarbage
        while (true) {
            val length = cipher.decryptLength(reader.readFully(Bip324Cipher.LENGTH_LEN))
            if (length > MAX_HANDSHAKE_CONTENTS_LEN) {
                throw TransportException.HandshakeFailed("Version packet too large: $length")
            }

            val packet = reader.readFully(Bip324Cipher.HEADER_LEN + length + AEAD_TAG_LEN)
            val decrypted = cipher.decrypt(packet, aad)
                ?: throw TransportException.HandshakeFailed("Authentication failed on the version packet")

            // Only the first packet in each direction is bound to the garbage.
            aad = ByteArray(0)
            if (!decrypted.ignore) return
        }
    }

    companion object {
        const val MAX_GARBAGE_LEN = 4095
        private const val ELLSWIFT_LEN = 64
        private const val AEAD_TAG_LEN = 16

        /**
         * The version packet is empty today. Capping it well below the wire ceiling keeps a hostile
         * peer from making us allocate megabytes before the session is even established.
         */
        private const val MAX_HANDSHAKE_CONTENTS_LEN = 4096
    }
}

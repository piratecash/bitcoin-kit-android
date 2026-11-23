package io.horizontalsystems.bitcoincore.network.transport.v2

import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.Bip324Aead
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.EllSwift
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.FSChaCha20
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.FSChaCha20Poly1305
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.HkdfSha256
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.TaggedHash

/** One decrypted BIP324 packet. Decoys still decrypt — they just carry no application message. */
internal class DecryptedPacket(val contents: ByteArray, val ignore: Boolean)

/**
 * BIP324 packet cipher: derives the session keys from the ElligatorSwift ECDH secret and
 * encrypts/decrypts packets (plan §2.2 step 6, §2.3).
 *
 * Packet layout: `[3B length, FSChaCha20][AEAD(header(1B) || contents, aad)][16B tag]`.
 */
internal class Bip324Cipher private constructor(
    private val sendLength: FSChaCha20,
    private val recvLength: FSChaCha20,
    private val sendPacket: FSChaCha20Poly1305,
    private val recvPacket: FSChaCha20Poly1305,
    val sendGarbageTerminator: ByteArray,
    val recvGarbageTerminator: ByteArray,
    val sessionId: ByteArray,
) {

    fun encrypt(contents: ByteArray, aad: ByteArray, ignore: Boolean = false): ByteArray {
        require(contents.size <= MAX_CONTENTS_LEN) { "contents too large: ${contents.size}" }

        val header = byteArrayOf(if (ignore) IGNORE_BIT else 0)
        val ciphertext = sendPacket.encrypt(aad, header + contents)
        val lengthField = byteArrayOf(
            (contents.size and 0xFF).toByte(),
            ((contents.size shr 8) and 0xFF).toByte(),
            ((contents.size shr 16) and 0xFF).toByte(),
        )
        return sendLength.crypt(lengthField) + ciphertext
    }

    /**
     * Test seam: encrypts a raw 3-byte length field.
     *
     * Lets the length cipher be exercised at its 24-bit boundaries without allocating the 16 MB
     * payloads that reaching those lengths through [encrypt] would require.
     */
    internal fun encryptLengthForTest(lengthField: ByteArray): ByteArray {
        require(lengthField.size == LENGTH_LEN) { "length field must be $LENGTH_LEN bytes" }

        return sendLength.crypt(lengthField)
    }

    /** Decrypts the 3-byte length prefix. Advances the length cipher, so call it exactly once per packet. */
    fun decryptLength(encryptedLength: ByteArray): Int {
        require(encryptedLength.size == LENGTH_LEN) { "length field must be $LENGTH_LEN bytes" }

        val plain = recvLength.crypt(encryptedLength)
        return (plain[0].toInt() and 0xFF) or
            ((plain[1].toInt() and 0xFF) shl 8) or
            ((plain[2].toInt() and 0xFF) shl 16)
    }

    /** Returns null on an AEAD tag mismatch; the caller must treat that as fatal. */
    fun decrypt(packet: ByteArray, aad: ByteArray): DecryptedPacket? {
        val plaintext = recvPacket.decrypt(aad, packet) ?: return null
        if (plaintext.isEmpty()) return null

        return DecryptedPacket(
            contents = plaintext.copyOfRange(HEADER_LEN, plaintext.size),
            ignore = (plaintext[0].toInt() and IGNORE_BIT.toInt()) != 0,
        )
    }

    fun wipe() {
        sendLength.wipe()
        recvLength.wipe()
        sendPacket.wipe()
        recvPacket.wipe()
        sendGarbageTerminator.fill(0)
        recvGarbageTerminator.fill(0)
        sessionId.fill(0)
    }

    companion object {
        const val LENGTH_LEN = 3
        const val HEADER_LEN = 1
        const val GARBAGE_TERMINATOR_LEN = 16

        /** 3-byte length field ceiling. The transport applies a much lower per-network cap. */
        const val MAX_CONTENTS_LEN = 0xFFFFFF

        /** Bytes a packet adds on top of its contents: length prefix + header + Poly1305 tag. */
        const val EXPANSION = LENGTH_LEN + HEADER_LEN + Bip324Aead.TAG_SIZE

        private const val IGNORE_BIT: Byte = 0x80.toByte()

        private const val SALT_PREFIX = "bitcoin_v2_shared_secret"
        private const val ECDH_TAG = "bip324_ellswift_xonly_ecdh"

        /**
         * Derives the session state.
         *
         * [magicBytes] must be the network's 4 message-start bytes in wire order — the salt is
         * per-network, so a byte-order slip here yields a stream neither side can decrypt with no
         * other symptom.
         */
        fun create(
            priv: ByteArray,
            ellswiftOurs: ByteArray,
            ellswiftTheirs: ByteArray,
            initiating: Boolean,
            magicBytes: ByteArray,
        ): Bip324Cipher {
            require(magicBytes.size == 4) { "network magic must be 4 bytes" }

            // Every secret temporary is tracked from the moment it exists, and the try covers the
            // ECDH itself, so a provider or allocation failure part-way through derivation cannot
            // leave live key material on the heap: on that path there is no cipher instance for the
            // caller to wipe, so this block is the only chance to erase it.
            val temporaries = ArrayList<ByteArray>(9)
            var sessionId: ByteArray? = null
            var ourTerminator: ByteArray? = null
            var theirTerminator: ByteArray? = null
            var constructed = false
            try {
                val ecdhX = EllSwift.ellswiftEcdhXonly(ellswiftTheirs, priv).also(temporaries::add)
                // The initiator's encoding always comes first, so both peers hash the same transcript.
                val transcript = if (initiating) {
                    ellswiftOurs + ellswiftTheirs + ecdhX
                } else {
                    ellswiftTheirs + ellswiftOurs + ecdhX
                }.also(temporaries::add)
                val sharedSecret = TaggedHash.hash(ECDH_TAG, transcript).also(temporaries::add)
                val salt = SALT_PREFIX.toByteArray(Charsets.US_ASCII) + magicBytes

                val initiatorL = derive(sharedSecret, salt, "initiator_L").also(temporaries::add)
                val initiatorP = derive(sharedSecret, salt, "initiator_P").also(temporaries::add)
                val responderL = derive(sharedSecret, salt, "responder_L").also(temporaries::add)
                val responderP = derive(sharedSecret, salt, "responder_P").also(temporaries::add)
                val terminators = derive(sharedSecret, salt, "garbage_terminators").also(temporaries::add)
                sessionId = derive(sharedSecret, salt, "session_id")

                ourTerminator = terminators.copyOfRange(0, GARBAGE_TERMINATOR_LEN)
                theirTerminator = terminators.copyOfRange(GARBAGE_TERMINATOR_LEN, 2 * GARBAGE_TERMINATOR_LEN)

                val cipher = Bip324Cipher(
                    sendLength = FSChaCha20(if (initiating) initiatorL else responderL),
                    recvLength = FSChaCha20(if (initiating) responderL else initiatorL),
                    sendPacket = FSChaCha20Poly1305(if (initiating) initiatorP else responderP),
                    recvPacket = FSChaCha20Poly1305(if (initiating) responderP else initiatorP),
                    sendGarbageTerminator = if (initiating) ourTerminator else theirTerminator,
                    recvGarbageTerminator = if (initiating) theirTerminator else ourTerminator,
                    sessionId = sessionId,
                )
                constructed = true
                return cipher
            } finally {
                // The ciphers copy their keys defensively, so wiping the derivation inputs here can
                // never zero a live key.
                temporaries.forEach { it.fill(0) }
                if (!constructed) {
                    // Only reachable when construction failed: on success these belong to the cipher.
                    sessionId?.fill(0)
                    ourTerminator?.fill(0)
                    theirTerminator?.fill(0)
                }
            }
        }

        private fun derive(sharedSecret: ByteArray, salt: ByteArray, label: String) =
            HkdfSha256.deriveKey(sharedSecret, salt, label.toByteArray(Charsets.US_ASCII), 32)
    }
}

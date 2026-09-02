package io.horizontalsystems.bitcoincore.network.transport.v2.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 5869 HKDF-SHA256 (extract-and-expand), implemented directly over `Mac("HmacSHA256")`
 * rather than a BouncyCastle HKDF wrapper, per project decision D2.
 */
internal object HkdfSha256 {

    private const val ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32
    private const val MAX_OUTPUT_LEN = 255 * HASH_LEN

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        // RFC 5869: an empty salt/key is replaced by HashLen zero bytes.
        // (SecretKeySpec also rejects a zero-length key array, so this covers both needs.)
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(HASH_LEN) else key, ALGORITHM))
        return mac.doFinal(data)
    }

    /** RFC 5869 step 2.2: PRK = HMAC-Hash(salt, IKM). */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    /** RFC 5869 step 2.3: OKM = T(1) | T(2) | ... truncated to [length] octets. */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..MAX_OUTPUT_LEN) { "HKDF output length out of range: $length" }
        val output = ByteArray(length)
        var previousT = ByteArray(0)
        try {
            var offset = 0
            var counter = 1
            while (offset < length) {
                val t = hmac(prk, previousT + info + byteArrayOf(counter.toByte()))
                previousT.fill(0) // wipe the prior T block before replacing it (§2.8)
                previousT = t
                val chunkSize = minOf(HASH_LEN, length - offset)
                previousT.copyInto(output, offset, 0, chunkSize)
                offset += chunkSize
                counter++
            }
            return output
        } finally {
            // For BIP324 (length == HASH_LEN) previousT duplicates the whole derived key; wipe it
            // regardless of output length (§2.8 secret-lifetime requirement).
            previousT.fill(0)
        }
    }

    /** Convenience combining [extract] and [expand], matching the `HKDF-SHA256(salt, ikm, info, len)` call sites in the handshake. */
    fun deriveKey(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = extract(salt, ikm)
        return try {
            expand(prk, info, length)
        } finally {
            prk.fill(0) // wipe the locally-computed PRK (§2.8 secret-lifetime requirement)
        }
    }
}

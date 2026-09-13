package io.horizontalsystems.bitcoincore.network.transport.v2.crypto

import java.security.MessageDigest

/**
 * BIP340 tagged hash: SHA256(SHA256(tag) || SHA256(tag) || data).
 * Domain-separates hash usage across different protocol contexts (e.g. BIP324's ECDH secret).
 */
internal object TaggedHash {

    fun hash(tag: String, data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val tagHash = digest.digest(tag.toByteArray(Charsets.UTF_8))
        digest.update(tagHash)
        digest.update(tagHash)
        digest.update(data)
        return digest.digest()
    }
}

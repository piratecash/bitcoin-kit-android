package io.horizontalsystems.bitcoincore.network.transport.v2.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Forward-secure rekeying wrapper stream cipher around [ChaCha20], ported line-for-line from
 * Bitcoin Core's reference implementation (`test_framework/crypto/chacha20.py`: `FSChaCha20`).
 *
 * Every [rekeyInterval] chunks, the key is replaced by the next 32 bytes of keystream, so
 * compromising the current key does not expose the plaintext of chunks encrypted under
 * previous keys (forward secrecy).
 */
internal class FSChaCha20(initialKey: ByteArray, private val rekeyInterval: Int = 224) {

    init {
        require(initialKey.size == 32) { "FSChaCha20 key must be 32 bytes, got ${initialKey.size}" }
    }

    // Defensive copy: the handshake wipes its own derived-key arrays right after constructing the
    // ciphers (§2.8). Aliasing the caller's array here would let that wipe zero the live key.
    private var key = initialKey.copyOf()
    private var blockCounter = 0
    private var chunkCounter = 0
    private var keystream = ByteArray(0)
    private var wiped = false

    private fun keystreamBytes(count: Int): ByteArray {
        while (keystream.size < count) {
            // Nonce = 4 zero bytes || 8-byte little-endian rekey epoch.
            val nonce = ByteArray(12)
            ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0, 0)
                .putLong(4, (chunkCounter / rekeyInterval).toLong())
            val grown = keystream + ChaCha20.block(key, nonce, blockCounter)
            keystream.fill(0) // wipe the shorter buffer superseded by the newly-grown one (§2.8)
            keystream = grown
            blockCounter++
        }
        val consumed = keystream
        val result = consumed.copyOfRange(0, count)
        keystream = consumed.copyOfRange(count, consumed.size)
        consumed.fill(0) // wipe the superseded keystream buffer (§2.8 secret-lifetime requirement)
        return result
    }

    fun crypt(chunk: ByteArray): ByteArray {
        val ks = keystreamBytes(chunk.size)
        val result = ByteArray(chunk.size) { i -> (ks[i].toInt() xor chunk[i].toInt()).toByte() }
        if ((chunkCounter + 1) % rekeyInterval == 0) {
            val newKey = keystreamBytes(32)
            key.fill(0) // wipe the superseded key (§2.8 secret-lifetime requirement)
            key = newKey
            blockCounter = 0
            keystream.fill(0) // wipe any leftover keystream buffered under the old epoch (§2.8)
            keystream = ByteArray(0)
        }
        chunkCounter++
        return result
    }

    /**
     * Zeros the live key and any buffered keystream (§2.8). Idempotent and safe to call on an
     * already-wiped instance - later phases call this from transport teardown.
     */
    fun wipe() {
        if (wiped) return
        key.fill(0)
        keystream.fill(0)
        wiped = true
    }

    /** Test-only: exposes whether [wipe] has zeroed the live key, without widening visibility beyond internal. */
    internal fun isKeyWiped(): Boolean = key.all { it == 0.toByte() }
}

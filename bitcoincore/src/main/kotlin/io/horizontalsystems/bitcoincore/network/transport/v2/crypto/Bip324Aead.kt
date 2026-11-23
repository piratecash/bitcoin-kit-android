package io.horizontalsystems.bitcoincore.network.transport.v2.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RFC 8439 AEAD_CHACHA20_POLY1305, backed by BouncyCastle's [ChaCha20Poly1305] engine.
 *
 * `javax.crypto.Cipher.getInstance("ChaCha20-Poly1305")` is only available from API 28, but this
 * project targets minSdkVersion 24, so BouncyCastle (already a project dependency) is used instead.
 */
internal object Bip324Aead {

    const val TAG_SIZE = 16
    private const val TAG_SIZE_BITS = TAG_SIZE * 8

    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), TAG_SIZE_BITS, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    /** Returns null if the authentication tag does not match (tampered/corrupt ciphertext). */
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (ciphertext.size < TAG_SIZE) return null
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), TAG_SIZE_BITS, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        return try {
            val len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            val finalLen = cipher.doFinal(out, len)
            out.copyOf(len + finalLen)
        } catch (e: InvalidCipherTextException) {
            null
        }
    }
}

/**
 * Forward-secure rekeying AEAD wrapper around [Bip324Aead], ported line-for-line from Bitcoin
 * Core's reference implementation (`test_framework/crypto/bip324_cipher.py`: `FSChaCha20Poly1305`).
 *
 * The nonce's low 4 bytes carry the packet index within the current rekey epoch (little-endian);
 * the high 8 bytes carry the epoch number. Every [rekeyInterval] packets the key is replaced by
 * encrypting 32 zero bytes under a reserved all-ones counter nonce.
 */
internal class FSChaCha20Poly1305(initialKey: ByteArray, private val rekeyInterval: Int = 224) {

    init {
        require(initialKey.size == 32) { "FSChaCha20Poly1305 key must be 32 bytes, got ${initialKey.size}" }
    }

    // Defensive copy: the handshake wipes its own derived-key arrays right after constructing the
    // ciphers (§2.8). Aliasing the caller's array here would let that wipe zero the live key.
    private var key = initialKey.copyOf()
    private var packetCounter = 0L
    private var wiped = false

    private fun currentNonce(): ByteArray {
        val nonce = ByteArray(12)
        ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0, (packetCounter % rekeyInterval).toInt())
            .putLong(4, packetCounter / rekeyInterval)
        return nonce
    }

    private fun rekeyIfDue(nonce: ByteArray) {
        if ((packetCounter + 1) % rekeyInterval == 0L) {
            val rekeyNonce = byteArrayOf(-1, -1, -1, -1) + nonce.copyOfRange(4, 12)
            val rekeyCiphertext = Bip324Aead.encrypt(key, rekeyNonce, ByteArray(0), ByteArray(32))
            val newKey = rekeyCiphertext.copyOf(32)
            rekeyCiphertext.fill(0) // wipe the temporary rekey ciphertext (§2.8 secret-lifetime requirement)
            key.fill(0) // wipe the superseded key (§2.8 secret-lifetime requirement)
            key = newKey
        }
        packetCounter++
    }

    fun encrypt(aad: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = currentNonce()
        val result = Bip324Aead.encrypt(key, nonce, aad, plaintext)
        rekeyIfDue(nonce)
        return result
    }

    fun decrypt(aad: ByteArray, ciphertext: ByteArray): ByteArray? {
        val nonce = currentNonce()
        val result = Bip324Aead.decrypt(key, nonce, aad, ciphertext)
        rekeyIfDue(nonce)
        return result
    }

    /** Zeros the live key (§2.8). Idempotent and safe to call on an already-wiped instance. */
    fun wipe() {
        if (wiped) return
        key.fill(0)
        wiped = true
    }

    /** Test-only: exposes whether [wipe] has zeroed the live key, without widening visibility beyond internal. */
    internal fun isKeyWiped(): Boolean = key.all { it == 0.toByte() }
}

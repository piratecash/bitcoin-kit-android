package io.horizontalsystems.bitcoincore.network.transport.v2.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ChaCha20 block function per RFC 8439, ported line-for-line from Bitcoin Core's reference
 * implementation (`test_framework/crypto/chacha20.py`: `chacha20_block`/`chacha20_doubleround`).
 *
 * Kotlin's [Int] arithmetic wraps modulo 2^32 on overflow, matching the reference's explicit
 * `& 0xffffffff` masking, so no unsigned integer type is needed here.
 */
internal object ChaCha20 {

    private const val KEY_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val BLOCK_SIZE = 64

    private val CONSTANTS = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)

    // Each row lists the four state indices touched by one quarter round of a double round.
    private val ROUND_INDICES = arrayOf(
        intArrayOf(0, 4, 8, 12), intArrayOf(1, 5, 9, 13), intArrayOf(2, 6, 10, 14), intArrayOf(3, 7, 11, 15),
        intArrayOf(0, 5, 10, 15), intArrayOf(1, 6, 11, 12), intArrayOf(2, 7, 8, 13), intArrayOf(3, 4, 9, 14),
    )

    private fun rotl32(v: Int, bits: Int): Int {
        val b = bits and 31
        return (v shl b) or (v ushr (32 - b))
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl32(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl32(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl32(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl32(s[b] xor s[c], 7)
    }

    private fun doubleRound(s: IntArray) {
        for (indices in ROUND_INDICES) {
            quarterRound(s, indices[0], indices[1], indices[2], indices[3])
        }
    }

    /**
     * Computes the 64-byte ChaCha20 keystream block for a 32-byte key, 12-byte nonce and
     * a 32-bit little-endian block counter.
     */
    fun block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        require(key.size == KEY_SIZE) { "ChaCha20 key must be $KEY_SIZE bytes" }
        require(nonce.size == NONCE_SIZE) { "ChaCha20 nonce must be $NONCE_SIZE bytes" }

        val keyBuf = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
        val nonceBuf = ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN)

        val init = IntArray(16)
        CONSTANTS.copyInto(init, 0)
        for (i in 0 until 8) init[4 + i] = keyBuf.getInt(i * 4)
        init[12] = counter
        for (i in 0 until 3) init[13 + i] = nonceBuf.getInt(i * 4)

        val state = init.copyOf()
        repeat(10) { doubleRound(state) } // 10 double rounds = 20 rounds
        for (i in 0 until 16) state[i] += init[i]

        val out = ByteBuffer.allocate(BLOCK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        for (v in state) out.putInt(v)
        return out.array()
    }
}

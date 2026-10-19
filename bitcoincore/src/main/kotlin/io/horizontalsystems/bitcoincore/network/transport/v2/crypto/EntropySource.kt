package io.horizontalsystems.bitcoincore.network.transport.v2.crypto

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Entropy source abstraction (plan §2.7). Injected rather than used via a seeded [SecureRandom]
 * directly, since a seeded SecureRandom's output is not guaranteed identical across JDK/provider
 * versions — deterministic tests need a dedicated fixed-byte implementation instead.
 */
internal interface IEntropySource {

    fun bytes(n: Int): ByteArray

    /**
     * Uniform integer in `[0, boundExclusive)`, unbiased via rejection sampling over a single
     * entropy byte at a time: masks the draw to the smallest power-of-two range covering
     * `[0, boundExclusive)` and resamples on out-of-range draws.
     *
     * A default (rather than requiring each implementation to invent its own) so every entropy
     * source shares the same tested rejection logic, backed by [bytes] - which lets a fixed-byte
     * test double deterministically exercise both endpoints and the rejection path.
     */
    fun nextInt(boundExclusive: Int): Int {
        require(boundExclusive >= 1) { "nextInt bound must be positive: $boundExclusive" }
        var mask = boundExclusive - 1
        mask = mask or (mask shr 1)
        mask = mask or (mask shr 2)
        mask = mask or (mask shr 4)
        mask = mask or (mask shr 8)
        mask = mask or (mask shr 16)
        // Enough bytes to cover the mask, so bounds above 256 (the handshake draws a garbage length
        // in 0..4095) are supported without falling back to a biased modulo reduction.
        val byteCount = (32 - Integer.numberOfLeadingZeros(mask.coerceAtLeast(1)) + 7) / 8
        while (true) {
            var candidate = 0
            for (byte in bytes(byteCount.coerceAtLeast(1))) {
                candidate = (candidate shl 8) or (byte.toInt() and 0xFF)
            }
            candidate = candidate and mask
            if (candidate < boundExclusive) return candidate
        }
    }

    /** Uniform scalar in `1..GROUP_ORDER-1`, encoded as 32 big-endian bytes. */
    fun scalar(): ByteArray = uniform(EllSwift.GROUP_ORDER).toFixed32Bytes()

    /** Uniform field element in `1..FIELD_MODULUS-1`. */
    fun fieldElement(): BigInteger = uniform(EllSwift.FIELD_MODULUS)

    /** Uniform case index in `0..7`, used to pick among ElligatorSwift's decode branches. */
    fun caseIndex(): Int = nextInt(8)

    /** Rejection-samples a uniform value in `1..(modulus-1)` from raw entropy bytes. */
    private fun uniform(modulus: BigInteger): BigInteger {
        val byteLen = (modulus.bitLength() + 7) / 8
        while (true) {
            val candidate = BigInteger(1, bytes(byteLen))
            if (candidate >= BigInteger.ONE && candidate < modulus) return candidate
        }
    }
}

/** Production entropy source. No weak/deterministic production path exists. */
internal class SecureRandomEntropySource(private val random: SecureRandom = SecureRandom()) : IEntropySource {

    override fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        random.nextBytes(out)
        return out
    }
}

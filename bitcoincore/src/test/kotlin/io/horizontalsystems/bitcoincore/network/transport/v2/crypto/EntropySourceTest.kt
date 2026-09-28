package io.horizontalsystems.bitcoincore.network.transport.v2.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class EntropySourceTest {

    // Deliberately does NOT override nextInt, so calls fall through to IEntropySource's default
    // (byte-masking rejection sampling backed by bytes()) - that is the logic under test here.
    private class ScriptedEntropySource(private val draws: List<Byte>) : IEntropySource {
        var consumed = 0
            private set

        override fun bytes(n: Int): ByteArray {
            require(n == 1) { "ScriptedEntropySource only supports single-byte draws: $n" }
            return byteArrayOf(draws[consumed++])
        }
    }

    @Test
    fun nextInt_lowEndpoint_isReachable() {
        val entropy = ScriptedEntropySource(listOf(0x00))

        assertEquals(0, entropy.nextInt(8))
    }

    @Test
    fun nextInt_highEndpoint_isReachable() {
        val entropy = ScriptedEntropySource(listOf(0x07))

        assertEquals(7, entropy.nextInt(8))
    }

    // boundExclusive = 8 is a power of two, so its mask (0x07) never admits an out-of-range
    // candidate - no draw could exercise rejection. A non-power-of-two bound is required: for
    // boundExclusive = 5 the mask is still 0x07, so masked draws of 5, 6 or 7 must be rejected
    // and resampled before a valid value in [0, 5) is accepted.
    @Test
    fun nextInt_outOfRangeMaskedDraw_rejectsAndResamplesUntilInRange() {
        val entropy = ScriptedEntropySource(listOf(0x06, 0x05, 0x02))

        val result = entropy.nextInt(5)

        assertEquals(2, result)
        assertEquals(3, entropy.consumed) // both rejected draws plus the accepted one
    }

    /**
     * Bounds above 256 need more than one entropy byte. Every other test double here feeds exactly
     * one byte per draw, so a regression that always consumed a single byte would still pass them
     * while silently capping the handshake's garbage length at 255 instead of 4095.
     */
    @Test
    fun nextInt_boundAbove256_consumesMultipleBytesAndReachesBothEndpoints() {
        // 4096 needs 12 mask bits => 2 bytes per draw.
        val zero = scripted(listOf(byteArrayOf(0x00, 0x00)))
        assertEquals(0, zero.nextInt(4096))

        val max = scripted(listOf(byteArrayOf(0xFF.toByte(), 0xFF.toByte())))
        assertEquals(4095, max.nextInt(4096))
    }

    @Test
    fun nextInt_nonPowerOfTwoBoundAbove256_rejectsOutOfRangeDraw() {
        // Bound 300 masks to 0..511, so 0x01F4 (500) is out of range and must be resampled.
        val source = scripted(
            listOf(
                byteArrayOf(0x01, 0xF4.toByte()), // 500 -> rejected
                byteArrayOf(0x00, 0x2A), // 42 -> accepted
            )
        )

        assertEquals(42, source.nextInt(300))
    }

    private fun scripted(draws: List<ByteArray>): IEntropySource {
        val queue = ArrayDeque(draws)
        return object : IEntropySource {
            override fun bytes(n: Int): ByteArray {
                val next = queue.removeFirst()
                require(next.size == n) { "test double expected a $n-byte draw, script provides ${next.size}" }
                return next
            }
        }
    }
}

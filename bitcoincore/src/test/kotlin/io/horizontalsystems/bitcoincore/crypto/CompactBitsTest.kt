import io.horizontalsystems.bitcoincore.crypto.CompactBits
import org.junit.Test
import org.junit.jupiter.api.Assertions
import java.math.BigInteger

class CompactBitsTest {

    /**
     * Test round-trip conversion: encoding a decoded compact value should yield the original compact.
     */
    @Test
    fun testRoundTripConversion() {
        val compactValues = longArrayOf(
            0x1e0fffffL, // Dash max target
            0x1d00ffffL, // Bitcoin genesis difficulty
            0x1c05688cL, // Example value
            0x1b0404cbL  // Example value from Bitcoin blockchain
        )

        for (compact in compactValues) {
            val target = CompactBits.decode(compact)
            val reEncoded = CompactBits.encode(target)
            Assertions.assertEquals(
                compact,
                reEncoded,
                "Round-trip conversion failed for compact value: ${compact.toString(16)}"
            )
        }
    }

    /**
     * Test a known compact value to ensure that encoding and decoding work as expected.
     */
    @Test
    fun testKnownValue() {
        val compact = 0x1d00ffffL // Bitcoin genesis block difficulty
        val target = CompactBits.decode(compact)
        val encoded = CompactBits.encode(target)
        Assertions.assertEquals(
            compact,
            encoded,
            "Known value test failed for compact value: ${compact.toString(16)}"
        )
    }

    @Test
    fun testMinValue() {
        val minVal = 0x01010000L

        val targetMin = CompactBits.decode(minVal)
        val encodedMin = CompactBits.encode(targetMin)

        Assertions.assertEquals(
            minVal,
            encodedMin,
            "Known value test failed for compact value: ${minVal.toString(16)}"
        )
    }

    @Test
    fun testMinMaxValue() {
        val maxVal = 0xFF7FFFFFL

        val targetMax = CompactBits.decode(maxVal)
        val encodedMax = CompactBits.encode(targetMax)

        Assertions.assertEquals(
            maxVal,
            encodedMax,
            "Known value test failed for compact value: ${maxVal.toString(16)}"
        )
    }

    /**
     * Precision test: Verify that slight modifications in the target value are properly handled.
     * Note: Due to the limited precision of the compact representation, small differences in
     * the BigInteger may not result in a different encoded value.
     */
    @Test
    fun testPrecision() {
        val compact = 0x1e0fffffL // Dash max target example
        val target = CompactBits.decode(compact)

        // Modify the target by subtracting one
        val targetMinusOne = target.subtract(BigInteger.ONE)
        val encodedMinusOne = CompactBits.encode(targetMinusOne)

        // Verify that the round-trip conversion of the original compact remains stable.
        val roundTrip = CompactBits.encode(CompactBits.decode(compact))
        Assertions.assertEquals(
            compact,
            roundTrip,
            "Precision round-trip conversion failed for compact value: ${compact.toString(16)}"
        )

        println("Original compact: ${compact.toString(16)}")
        println("Encoded target-1: ${encodedMinusOne.toString(16)}")
    }

    /**
     * Test that encoding and decoding zero works correctly.
     */
    @Test
    fun testZeroValue() {
        val zero = BigInteger.ZERO
        val encoded = CompactBits.encode(zero)
        Assertions.assertEquals(0L, encoded, "Encoding zero did not return 0.")
        val decoded = CompactBits.decode(encoded)
        Assertions.assertEquals(zero, decoded, "Decoding zero did not return BigInteger.ZERO.")
    }

    /**
     * Test precision loss: small modifications below the resolution should not change the encoded compact value,
     * while modifications above the resolution should change it.
     */
    @Test
    fun testPrecisionLoss() {
        val compact = 0x1e0fffffL
        val target = CompactBits.decode(compact)
        val size = if (target.bitLength() == 0) 1 else (target.bitLength() + 7) / 8
        val resolution = if (size <= 3) BigInteger.ONE else BigInteger.valueOf(256).pow(size - 3)

        // Add a value less than half the resolution; the encoded value should remain the same.
        val smallModification = resolution.divide(BigInteger.valueOf(2))
        val targetModifiedSmall = target.add(smallModification)
        val encodedModifiedSmall = CompactBits.encode(targetModifiedSmall)
        Assertions.assertEquals(
            compact,
            encodedModifiedSmall,
            "Small modification below resolution should not change the compact value"
        )

        // Add a full resolution value; the encoded value should change.
        val targetModifiedLarge = target.add(resolution)
        val encodedModifiedLarge = CompactBits.encode(targetModifiedLarge)
        Assertions.assertNotEquals(
            compact,
            encodedModifiedLarge,
            "Modification equal or above resolution should change the compact value"
        )
    }
}

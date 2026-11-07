package io.horizontalsystems.bitcoincore.network.transport.v2.crypto

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class EllSwiftTest {

    // Deterministic entropy source for tests, backed by java.util.Random rather than SecureRandom:
    // java.util.Random's LCG algorithm is specified by the JDK and guaranteed to produce the same
    // sequence for a given seed on every platform, unlike SecureRandom.
    private class FixedEntropySource(seed: Long) : IEntropySource {
        private val random = java.util.Random(seed)

        override fun bytes(n: Int): ByteArray {
            val out = ByteArray(n)
            random.nextBytes(out)
            return out
        }

        override fun nextInt(boundExclusive: Int): Int = random.nextInt(boundExclusive)
    }

    private fun readCsvRows(resourceName: String): List<List<String>> {
        val stream = checkNotNull(EllSwiftTest::class.java.getResourceAsStream(resourceName)) {
            "missing test resource: $resourceName"
        }
        return stream.bufferedReader().useLines { lines ->
            lines.drop(1).filter { it.isNotBlank() }.map { it.split(",") }.toList()
        }
    }

    // Ported from Bitcoin Core's test_framework/crypto/ellswift.py: test_xswiftec, using the
    // vendored ellswift_decode_test_vectors.csv (each row: a 64-byte ellswift encoding and the
    // curve X coordinate it must decode to).
    @Test
    fun xswiftec_decodeVectors_matchesReference() {
        val rows = readCsvRows("/bip324/ellswift_decode_test_vectors.csv")
        // Without this the test passes vacuously if the resource ever goes missing or the parser
        // returns nothing — the failure mode a vector test exists to prevent.
        assertEquals("vendored decode vectors", 76, rows.size)
        for ((ellswiftHex, xHex) in rows) {
            val encoding = ellswiftHex.hexToBytes()
            val u = BigInteger(1, encoding.copyOfRange(0, 32))
            val t = BigInteger(1, encoding.copyOfRange(32, 64))
            val expectedX = BigInteger(1, xHex.hexToBytes())

            val x = EllSwift.xswiftec(u, t)

            assertEquals(expectedX, x)
            assertTrue(EllSwift.isValidX(x))
        }
    }

    // Ported from Bitcoin Core's test_framework/crypto/ellswift.py: test_xswiftec_inv, using the
    // vendored xswiftec_inv_test_vectors.csv (each row: u, x, and the expected t for cases 0..7,
    // with an empty column meaning that case has no solution for that row).
    @Test
    fun xswiftecInv_testVectors_matchesReference() {
        val rows = readCsvRows("/bip324/xswiftec_inv_test_vectors.csv")
        assertEquals("vendored inverse vectors", 32, rows.size)
        for (row in rows) {
            val u = BigInteger(1, row[0].hexToBytes())
            val x = BigInteger(1, row[1].hexToBytes())
            for (case in 0 until 8) {
                val expectedHex = row[2 + case]
                val actual = EllSwift.xswiftecInv(x, u, case)
                if (expectedHex.isEmpty()) {
                    assertNull("case $case unexpectedly produced a value", actual)
                } else {
                    val t = checkNotNull(actual) { "case $case unexpectedly returned null" }
                    assertEquals("case $case", expectedHex, t.toFixed32Bytes().toHex())
                    assertEquals("case $case round-trip", x, EllSwift.xswiftec(u, t))
                }
            }
        }
    }

    // Stronger than merely checking the decoded value is *a* valid curve X: independently
    // computes the X coordinate of priv * G (via BouncyCastle directly, not through EllSwift) and
    // asserts the ellswiftCreate()-then-xswiftec() round trip decodes to that exact value - a
    // decode that lands on some other valid-but-wrong curve point would otherwise go undetected.
    @Test
    fun ellswiftCreate_thenXswiftec_decodesToXCoordinateOfPrivTimesG() {
        val priv = BigInteger.valueOf(123_456_789L).toFixed32Bytes()
        val curve = CustomNamedCurves.getByName("secp256k1")
        val scalar = BigInteger(1, priv).mod(EllSwift.GROUP_ORDER)
        val expectedX = curve.g.multiply(scalar).normalize().affineXCoord.toBigInteger()

        val encoded = EllSwift.ellswiftCreate(priv, FixedEntropySource(seed = 7))

        assertEquals(64, encoded.size)
        val u = BigInteger(1, encoded.copyOfRange(0, 32))
        val t = BigInteger(1, encoded.copyOfRange(32, 64))
        val decodedX = EllSwift.xswiftec(u, t)
        assertTrue(EllSwift.isValidX(decodedX))
        assertEquals(expectedX, decodedX)
    }

    // Stronger round-trip check than the above: two peers each derive an ellswiftCreate() encoding
    // with a fixed entropy source, exchange them, and compute X-only ECDH (which internally decodes
    // the peer's encoding via xswiftec). Standard ECDH symmetry (privA * (privB * G) == privB *
    // (privA * G)) means both sides must land on the same shared secret only if encode/decode are
    // mutually consistent.
    @Test
    fun ellswiftCreate_thenEcdhXonly_derivesSymmetricSharedSecret() {
        val entropy = FixedEntropySource(seed = 1)
        val privA = BigInteger.valueOf(111_111L).toFixed32Bytes()
        val privB = BigInteger.valueOf(222_222L).toFixed32Bytes()

        val ellswiftA = EllSwift.ellswiftCreate(privA, entropy)
        val ellswiftB = EllSwift.ellswiftCreate(privB, entropy)
        val sharedFromA = EllSwift.ellswiftEcdhXonly(ellswiftB, privA)
        val sharedFromB = EllSwift.ellswiftEcdhXonly(ellswiftA, privB)

        assertEquals(sharedFromA.toHex(), sharedFromB.toHex())
    }

    // Verifies IEntropySource's shared rejection-sampling loop (uniform(), backing scalar())
    // correctly rejects 0 and values >= GROUP_ORDER before accepting a valid draw.
    @Test
    fun scalar_rejectsZeroAndOutOfRangeDraws_returnsFirstValidValue() {
        val validValue = BigInteger.valueOf(42)
        val draws = listOf(
            ByteArray(32), // 0 -> must be rejected
            ByteArray(32) { 0xFF.toByte() }, // 2^256 - 1, >= GROUP_ORDER -> must be rejected
            validValue.toFixed32Bytes(),
        )
        var index = 0
        val entropy = object : IEntropySource {
            override fun bytes(n: Int): ByteArray = draws[index++]
            override fun nextInt(boundExclusive: Int): Int = 0
        }

        val scalar = entropy.scalar()

        assertEquals(3, index) // consumed both rejected draws plus the accepted one
        assertEquals(validValue, BigInteger(1, scalar))
    }

    @Test
    fun sqrt_knownNonResidue_returnsNull() {
        // 7 is a quadratic non-residue mod the secp256k1 field prime (verified independently via
        // Euler's criterion: 7^((p-1)/2) mod p == p-1).
        assertNull(EllSwift.sqrt(BigInteger.valueOf(7)))
    }

    @Test
    fun sqrt_knownResidue_returnsVerifiedRoot() {
        val root = checkNotNull(EllSwift.sqrt(BigInteger.valueOf(4)))

        assertEquals(BigInteger.valueOf(4), root.multiply(root).mod(EllSwift.FIELD_MODULUS))
    }
}

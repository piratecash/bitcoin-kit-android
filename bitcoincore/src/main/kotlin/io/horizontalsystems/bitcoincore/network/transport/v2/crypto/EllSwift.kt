package io.horizontalsystems.bitcoincore.network.transport.v2.crypto

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger

/**
 * ElligatorSwift encoding/decoding and X-only ECDH for secp256k1, ported line-for-line from
 * Bitcoin Core's reference implementation (`test_framework/crypto/ellswift.py` and
 * `test_framework/crypto/secp256k1.py`).
 *
 * All field arithmetic below operates modulo [FIELD_MODULUS] (the curve's base field `p`), which
 * is distinct from [GROUP_ORDER] (the curve's order `n`) used for private-key scalars. Mixing the
 * two up is a classic secp256k1 bug, so both are hardcoded here for auditability rather than one
 * being derived from the other at runtime.
 */
internal object EllSwift {

    /** secp256k1 field modulus p = 2^256 - 2^32 - 977. */
    val FIELD_MODULUS: BigInteger = BigInteger.valueOf(2).pow(256)
        .subtract(BigInteger.valueOf(2).pow(32))
        .subtract(BigInteger.valueOf(977))

    /** secp256k1 group order n (number of points on the curve). */
    val GROUP_ORDER: BigInteger =
        BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val FOUR = BigInteger.valueOf(4)
    private val SEVEN = BigInteger.valueOf(7)

    private val curveParams = CustomNamedCurves.getByName("secp256k1")
    private val generator: ECPoint = curveParams.g

    private fun fAdd(a: BigInteger, b: BigInteger) = a.add(b).mod(FIELD_MODULUS)
    private fun fSub(a: BigInteger, b: BigInteger) = a.subtract(b).mod(FIELD_MODULUS)
    private fun fMul(a: BigInteger, b: BigInteger) = a.multiply(b).mod(FIELD_MODULUS)
    private fun fDiv(a: BigInteger, b: BigInteger) = a.multiply(b.modInverse(FIELD_MODULUS)).mod(FIELD_MODULUS)
    private fun fPow(a: BigInteger, e: BigInteger) = a.modPow(e, FIELD_MODULUS)
    private fun fNeg(a: BigInteger) = a.negate().mod(FIELD_MODULUS)

    /**
     * Square root mod p. Since p % 4 == 3, Tonelli-Shanks reduces to `a^((p+1)/4) mod p`; the
     * result is verified by squaring, returning null (instead of a wrong root) when [a] is not a
     * quadratic residue.
     */
    internal fun sqrt(a: BigInteger): BigInteger? {
        val reduced = a.mod(FIELD_MODULUS)
        val exponent = FIELD_MODULUS.add(BigInteger.ONE).divide(FOUR)
        val root = reduced.modPow(exponent, FIELD_MODULUS)
        return if (fMul(root, root) == reduced) root else null
    }

    /** Precomputed square root of -3 mod p, used throughout the ElligatorSwift map. */
    private val MINUS_3_SQRT: BigInteger = checkNotNull(sqrt(fNeg(THREE))) { "-3 must be a QR mod p for secp256k1" }

    /** Whether [x] is a valid X coordinate on the curve, i.e. x^3 + 7 is a square mod p. */
    internal fun isValidX(x: BigInteger): Boolean = sqrt(fAdd(fPow(x, THREE), SEVEN)) != null

    /** Decodes field elements (u, t) to an X coordinate on the curve (the ElligatorSwift map). */
    internal fun xswiftec(uIn: BigInteger, tIn: BigInteger): BigInteger {
        var u = uIn.mod(FIELD_MODULUS)
        var t = tIn.mod(FIELD_MODULUS)
        if (u == BigInteger.ZERO) u = BigInteger.ONE
        if (t == BigInteger.ZERO) t = BigInteger.ONE
        if (fAdd(fAdd(fPow(u, THREE), fPow(t, TWO)), SEVEN) == BigInteger.ZERO) {
            t = fMul(TWO, t)
        }
        val x0 = fDiv(fSub(fAdd(fPow(u, THREE), SEVEN), fPow(t, TWO)), fMul(TWO, t))
        val y = fDiv(fAdd(x0, t), fMul(MINUS_3_SQRT, u))
        val candidates = listOf(
            fAdd(u, fMul(FOUR, fPow(y, TWO))),
            fDiv(fSub(fDiv(fNeg(x0), y), u), TWO),
            fDiv(fSub(fDiv(x0, y), u), TWO),
        )
        return candidates.firstOrNull { isValidX(it) }
            ?: error("xswiftec: no valid x found among the three candidates (unreachable for field elements)")
    }

    /**
     * Given x and u, finds t such that `xswiftec(u, t) == x`, or null. [case] selects which of up
     * to 8 results to return.
     */
    internal fun xswiftecInv(xIn: BigInteger, uIn: BigInteger, case: Int): BigInteger? {
        val x = xIn.mod(FIELD_MODULUS)
        val u = uIn.mod(FIELD_MODULUS)
        val s: BigInteger
        val v: BigInteger
        if (case and 2 == 0) {
            if (isValidX(fSub(fNeg(x), u))) return null
            v = x
            val denom = fAdd(fAdd(fPow(u, TWO), fMul(u, v)), fPow(v, TWO))
            s = fNeg(fDiv(fAdd(fPow(u, THREE), SEVEN), denom))
        } else {
            s = fSub(x, u)
            if (s == BigInteger.ZERO) return null
            val inner = fAdd(fMul(FOUR, fAdd(fPow(u, THREE), SEVEN)), fMul(THREE, fMul(s, fPow(u, TWO))))
            val r = sqrt(fMul(fNeg(s), inner)) ?: return null
            if (case and 1 != 0 && r == BigInteger.ZERO) return null
            v = fDiv(fAdd(fNeg(u), fDiv(r, s)), TWO)
        }
        val w = sqrt(s) ?: return null
        val halfOneMinus3Sqrt = fDiv(fSub(BigInteger.ONE, MINUS_3_SQRT), TWO)
        val halfOnePlus3Sqrt = fDiv(fAdd(BigInteger.ONE, MINUS_3_SQRT), TWO)
        return when (case and 5) {
            0 -> fNeg(fMul(w, fAdd(fMul(u, halfOneMinus3Sqrt), v)))
            1 -> fMul(w, fAdd(fMul(u, halfOnePlus3Sqrt), v))
            4 -> fMul(w, fAdd(fMul(u, halfOneMinus3Sqrt), v))
            5 -> fNeg(fMul(w, fAdd(fMul(u, halfOnePlus3Sqrt), v)))
            else -> error("unreachable: case and 5 only yields 0, 1, 4 or 5")
        }
    }

    /** Given a valid curve X coordinate, finds (u, t) that ElligatorSwift-encode it, using [entropy]. */
    internal fun xelligatorswift(x: BigInteger, entropy: IEntropySource): Pair<BigInteger, BigInteger> {
        require(isValidX(x.mod(FIELD_MODULUS))) { "x is not a valid curve x-coordinate" }
        while (true) {
            val u = entropy.fieldElement()
            val case = entropy.caseIndex()
            val t = xswiftecInv(x, u, case)
            if (t != null) return u to t
        }
    }

    /** Derives the 64-byte ElligatorSwift-encoded public key for private scalar [priv]. */
    internal fun ellswiftCreate(priv: ByteArray, entropy: IEntropySource): ByteArray {
        val scalar = BigInteger(1, priv).mod(GROUP_ORDER)
        val point = generator.multiply(scalar).normalize()
        val x = point.affineXCoord.toBigInteger()
        val (u, t) = xelligatorswift(x, entropy)
        return u.toFixed32Bytes() + t.toFixed32Bytes()
    }

    /** Computes the X-only ECDH shared secret between our [priv] and [theirEllswift]'s 64-byte encoding. */
    internal fun ellswiftEcdhXonly(theirEllswift: ByteArray, priv: ByteArray): ByteArray {
        require(theirEllswift.size == 64) { "ellswift encoding must be 64 bytes" }
        val u = BigInteger(1, theirEllswift.copyOfRange(0, 32)).mod(FIELD_MODULUS)
        val t = BigInteger(1, theirEllswift.copyOfRange(32, 64)).mod(FIELD_MODULUS)
        val x = xswiftec(u, t)
        val point = liftX(x)
        val scalar = BigInteger(1, priv).mod(GROUP_ORDER)
        val shared = point.multiply(scalar).normalize()
        return shared.affineXCoord.toBigInteger().toFixed32Bytes()
    }

    /** Lifts an X coordinate to a curve point using the even-y convention. */
    private fun liftX(x: BigInteger): ECPoint {
        val ySquared = fAdd(fPow(x, THREE), SEVEN)
        var y = checkNotNull(sqrt(ySquared)) { "x is not a valid curve x-coordinate" }
        if (y.testBit(0)) {
            y = fNeg(y)
        }
        return curveParams.curve.createPoint(x, y)
    }
}

/** Encodes a non-negative [BigInteger] as a fixed-width 32-byte big-endian array. */
internal fun BigInteger.toFixed32Bytes(): ByteArray {
    val raw = toByteArray()
    return when {
        raw.size == 32 -> raw
        raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
        else -> ByteArray(32 - raw.size) + raw
    }
}

package io.horizontalsystems.bitcoincore.crypto;

import java.math.BigInteger;

import io.horizontalsystems.bitcoincore.utils.Utils;

public class CompactBits {

    /**
     * The "compact" format is a representation of a whole
     * number N using an unsigned 32bit number similar to a
     * floating point format.
     * The most significant 8 bits are the unsigned exponent of base 256.
     * This exponent can be thought of as "number of bytes of N".
     * The lower 23 bits are the mantissa.
     * Bit number 24 (0x800000) represents the sign of N.
     * N = (-1^sign) * mantissa * 256^(exponent-3)
     *
     * Satoshi's original implementation used BN_bn2mpi() and BN_mpi2bn().
     * MPI uses the most significant bit of the first byte as sign.
     * Thus 0x1234560000 is compact (0x05123456)
     * and  0xc0de000000 is compact (0x0600c0de)
     *
     * Bitcoin only uses this "compact" format for encoding difficulty
     * targets, which are unsigned 256bit quantities.  Thus, all the
     * complexities of the sign bit and using base 256 are probably an
     * implementation accident.
     */
    public static BigInteger decode(long compact) {
        int nSize = (int)(compact >>> 24);
        long nWord = compact & 0x007fffffL;

        boolean negative = (nWord != 0) && ((compact & 0x00800000L) != 0);

        BigInteger result = BigInteger.valueOf(nWord);

        if (nSize <= 3) {
            int shift = 8 * (3 - nSize);
            result = result.shiftRight(shift);
        } else {
            int shift = 8 * (nSize - 3);
            result = result.shiftLeft(shift);
        }

        if (negative) {
            result = result.negate();
        }
        return result;
    }

    /**
     * @see CompactBits#decode
     */
    public static long encode(BigInteger value) {
        boolean negative = value.signum() < 0;
        BigInteger v = value.abs();

        int size = (v.bitLength() + 7) / 8;
        long mantissa;

        if (size <= 3) {
            mantissa = v.longValue() << (8 * (3 - size));
        } else {
            BigInteger shifted = v.shiftRight(8 * (size - 3));
            mantissa = shifted.and(BigInteger.valueOf(0xffffffffffffffffL)).longValue();
        }

        if ((mantissa & 0x00800000L) != 0) {
            mantissa >>= 8;
            size++;
        }

        long compact = mantissa & 0x007fffff;
        compact |= (long) size << 24;

        if (negative && (compact & 0x007fffffL) != 0) {
            compact |= 0x00800000L;
        }
        return compact;
    }

}

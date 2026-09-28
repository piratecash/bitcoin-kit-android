package io.horizontalsystems.dashlib

import org.junit.Assert.assertFalse
import org.junit.Test

class BLSTest {

    /**
     * No mocks: in a host unit test the native genuinely is absent, which is the state
     * under test. Two calls, because the second hits a different failure — the first
     * throws UnsatisfiedLinkError, the second NoClassDefFoundError from the poisoned
     * class initializer.
     */
    @Test
    fun verifySignature_nativeNotLoaded_returnsFalseOnRepeatedCalls() {
        val bls = BLS()
        val pubKeyOperator = ByteArray(48)
        val signature = ByteArray(96)
        val hash = ByteArray(32)

        assertFalse(bls.verifySignature(pubKeyOperator, signature, hash))
        assertFalse(bls.verifySignature(pubKeyOperator, signature, hash))
    }
}

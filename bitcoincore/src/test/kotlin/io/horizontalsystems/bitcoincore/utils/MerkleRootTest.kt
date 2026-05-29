package io.horizontalsystems.bitcoincore.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MerkleRootTest {

    @Test
    fun calculate_singleHash_returnsSameHash() {
        val hash = hash(1)

        assertArrayEquals(hash, MerkleRoot.calculate(listOf(hash)))
    }

    @Test
    fun calculate_twoHashes_returnsParentHash() {
        val first = hash(1)
        val second = hash(2)

        assertArrayEquals(parent(first, second), MerkleRoot.calculate(listOf(first, second)))
    }

    @Test
    fun calculate_oddHashCount_duplicatesLastHash() {
        val first = hash(1)
        val second = hash(2)
        val third = hash(3)
        val left = parent(first, second)
        val right = parent(third, third)

        assertArrayEquals(parent(left, right), MerkleRoot.calculate(listOf(first, second, third)))
    }

    private fun hash(seed: Int): ByteArray {
        return ByteArray(HASH_SIZE) { index -> (seed + index).toByte() }
    }

    private fun parent(left: ByteArray, right: ByteArray): ByteArray {
        return Utils.doubleDigestTwoBuffers(left, 0, HASH_SIZE, right, 0, HASH_SIZE)
    }

    private companion object {
        const val HASH_SIZE = 32
    }
}

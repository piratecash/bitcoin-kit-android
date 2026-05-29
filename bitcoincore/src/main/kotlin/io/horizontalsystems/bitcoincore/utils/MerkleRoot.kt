package io.horizontalsystems.bitcoincore.utils

object MerkleRoot {

    fun calculate(hashes: List<ByteArray>): ByteArray {
        require(hashes.isNotEmpty()) { "hashes must not be empty" }

        var level = hashes
        while (level.size > 1) {
            level = level.chunked(2).map { pair ->
                val left = pair[0]
                val right = pair.getOrElse(1) { left }
                Utils.doubleDigestTwoBuffers(left, 0, HASH_SIZE, right, 0, HASH_SIZE)
            }
        }

        return level.first()
    }

    private const val HASH_SIZE = 32
}

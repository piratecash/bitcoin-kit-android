package io.horizontalsystems.cosantakit.masternodelist

import io.horizontalsystems.cosantakit.models.Quorum

class QuorumListMerkleRootCalculator(private val merkleRootCreator: MerkleRootCreator) {

    fun calculateMerkleRoot(sortedQuorums: List<Quorum>): ByteArray? {
        return merkleRootCreator.create(sortedQuorums.map { it.hash })
    }

}

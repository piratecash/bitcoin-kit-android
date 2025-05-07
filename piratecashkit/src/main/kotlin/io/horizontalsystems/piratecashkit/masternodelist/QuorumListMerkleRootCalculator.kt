package io.horizontalsystems.piratecashkit.masternodelist

import io.horizontalsystems.piratecashkit.models.Quorum

class QuorumListMerkleRootCalculator(private val merkleRootCreator: MerkleRootCreator) {

    fun calculateMerkleRoot(sortedQuorums: List<Quorum>): ByteArray? {
        return merkleRootCreator.create(sortedQuorums.map { it.hash })
    }

}

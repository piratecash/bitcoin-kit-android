package io.horizontalsystems.piratecashkit.masternodelist

import io.horizontalsystems.piratecashkit.models.Masternode

class MasternodeListMerkleRootCalculator(val masternodeMerkleRootCreator: MerkleRootCreator) {

    fun calculateMerkleRoot(sortedMasternodes: List<Masternode>): ByteArray? {
        return masternodeMerkleRootCreator.create(sortedMasternodes.map { it.hash })
    }

}

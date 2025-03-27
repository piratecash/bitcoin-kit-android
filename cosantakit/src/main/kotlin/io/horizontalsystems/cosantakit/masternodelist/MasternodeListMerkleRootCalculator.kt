package io.horizontalsystems.cosantakit.masternodelist

import io.horizontalsystems.cosantakit.models.Masternode

class MasternodeListMerkleRootCalculator(val masternodeMerkleRootCreator: MerkleRootCreator) {

    fun calculateMerkleRoot(sortedMasternodes: List<Masternode>): ByteArray? {
        return masternodeMerkleRootCreator.create(sortedMasternodes.map { it.hash })
    }

}

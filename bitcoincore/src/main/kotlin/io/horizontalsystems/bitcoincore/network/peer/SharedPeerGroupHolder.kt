package io.horizontalsystems.bitcoincore.network.peer

import io.horizontalsystems.bitcoincore.blocks.BloomFilterLoader
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer

class SharedPeerGroupHolder(
    val peerGroup: SharedPeerGroup,
    val peerManager: PeerManager,
    val bloomFilterManager: BloomFilterManager,
    val networkMessageParser: NetworkMessageParser,
    val networkMessageSerializer: NetworkMessageSerializer
) {
    private val bloomFilterLoader = BloomFilterLoader(bloomFilterManager, peerManager)

    init {
        bloomFilterManager.listener = bloomFilterLoader
        peerGroup.addPeerGroupListener(bloomFilterLoader)
    }
}

package io.horizontalsystems.bitcoincore.network.peer

import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IPeerAddressManager
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer

class SharedPeerGroup(
    hostManager: IPeerAddressManager,
    network: Network,
    peerManager: PeerManager,
    peerSize: Int,
    networkMessageParser: NetworkMessageParser,
    networkMessageSerializer: NetworkMessageSerializer,
    connectionManager: IConnectionManager,
    localDownloadedBestBlockHeight: Int,
    handleAddrMessage: Boolean
) : PeerGroup(
    hostManager, network, peerManager, peerSize,
    networkMessageParser, networkMessageSerializer,
    connectionManager, localDownloadedBestBlockHeight, handleAddrMessage
) {
    private var startCount = 0

    @Synchronized
    override fun start() {
        startCount++
        if (startCount == 1) {
            super.start()
        }
    }

    @Synchronized
    override fun stop() {
        if (startCount <= 0) return
        startCount--
        if (startCount == 0) {
            super.stop()
        }
    }

    @Synchronized
    fun forceStop() {
        startCount = 0
        super.stop()
    }
}

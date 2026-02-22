package io.horizontalsystems.bitcoincore.network.peer

import io.horizontalsystems.bitcoincore.core.IPeerAddressManager
import io.horizontalsystems.bitcoincore.core.IPeerAddressManagerListener
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.models.PeerAddress
import io.horizontalsystems.bitcoincore.network.Network
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class PeerAddressManager(private val network: Network, private val storage: IStorage) :
    IPeerAddressManager {

    override var listener: IPeerAddressManagerListener? = null

    private val state = State()
    private val logger = Logger.getLogger("PeerHostManager")
    private val peerDiscover = PeerDiscover(this, network.logTag)
    private val checkedHosts = ConcurrentHashMap.newKeySet<String>()

    override val hasFreshIps: Boolean
        get() {
            getLeastScoreFastestPeer()?.let { peerAddress ->
                return peerAddress.connectionTime == null
            }

            return false
        }

    override fun getIp(): String? {
        val peerAddress = getLeastScoreFastestPeer()
        if (peerAddress == null) {
            val dnsListToCheck = network.dnsSeeds.filter { !checkedHosts.contains(it) }
            if (dnsListToCheck.isNotEmpty()) {
                peerDiscover.lookup(dnsListToCheck)
            }
            return null
        }

        state.add(peerAddress.ip)

        return peerAddress.ip
    }

    @Synchronized
    override fun addIps(host: String?, ips: List<String>) {
        if (ips.isNotEmpty() && host != null) {
            checkedHosts.add(host)
        }
        storage.setPeerAddresses(ips.map { PeerAddress(it, 0) })

        logger.info("${network.logTag}: Added new addresses: ${ips.size}")

        listener?.onAddAddress()
    }

    override fun addUnreachedHosts(host: String) = storage.addUnreachedHosts(host)

    @Synchronized
    override fun markFailed(ip: String) {
        state.remove(ip)

        storage.deletePeerAddress(ip)
    }

    override fun markSuccess(ip: String) {
        state.remove(ip)
    }

    @Synchronized
    override fun markConnected(peer: Peer) {
        storage.markConnected(peer.host, peer.connectionTime)
    }

    private fun getLeastScoreFastestPeer(): PeerAddress? {
        return storage.getLeastScoreFastestPeerAddressExcludingIps(state.getUsedPeers())
    }

    private class State {
        private var usedPeers = mutableListOf<String>()

        @Synchronized
        fun getUsedPeers(): List<String> {
            return usedPeers.toList()
        }

        @Synchronized
        fun add(ip: String) {
            usedPeers.add(ip)
        }

        @Synchronized
        fun remove(ip: String) {
            usedPeers.removeAll { it == ip }
        }
    }
}

package io.horizontalsystems.bitcoincore.network.peer

import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.Volatile

class PeerManager {

    private var peers = ConcurrentHashMap<String, Peer>()
    @Volatile
    private var allowBroadcastFromUnsyncedPeers = false

    val peersCount: Int
        get() = peers.size

    fun add(peer: Peer) {
        peers[peer.host] = peer
    }

    fun remove(peer: Peer) {
        peers.remove(peer.host)
    }

    fun setAllowBroadcastFromUnsyncedPeers(value: Boolean) {
        allowBroadcastFromUnsyncedPeers = value
    }

    fun disconnectAll() {
        peers.values.forEach { it.close() }
        peers.clear()
    }

    fun connected(): List<Peer> {
        // A peer still inside the chain-identity probe has completed the
        // version/verack handshake (Peer.connected = true) but has not yet
        // been confirmed to be on our chain. It must not appear in this list:
        // callers iterate it to broadcast (e.g. BloomFilterLoader.onFilterUpdated),
        // and on sister chains that share the P2P magic and port
        // (eCash ↔ BCH) a wrong-chain peer would otherwise receive our bloom
        // filter before the probe resolves. Same invariant as in readyPears().
        return peers.values.filter { it.connected && !it.awaitingChainIdentity }
    }

    fun sorted(): List<Peer> {
        return connected().sortedBy { it.connectionTime }
    }

    fun readyPears(): List<Peer> {
        return peers.values.filter { peer ->
            // A peer still verifying its chain identity must never receive a broadcast, even when
            // broadcasting from unsynced peers is allowed: it may turn out to be on the wrong chain.
            peer.connected && !peer.awaitingChainIdentity && (allowBroadcastFromUnsyncedPeers || peer.ready)
        }
    }

    fun hasSyncedPeer(): Boolean {
        return peers.values.any { it.connected && it.synced }
    }

}

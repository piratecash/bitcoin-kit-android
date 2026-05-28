package io.horizontalsystems.bitcoincore.network.peer

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerManagerReadyPearsTest {

    private val peerManager = PeerManager()

    private fun addPeer(
        host: String,
        connected: Boolean = true,
        ready: Boolean = true,
        awaitingChainIdentity: Boolean = false,
    ): Peer {
        val peer = mock<Peer>()
        whenever(peer.host).thenReturn(host)
        whenever(peer.connected).thenReturn(connected)
        whenever(peer.ready).thenReturn(ready)
        whenever(peer.awaitingChainIdentity).thenReturn(awaitingChainIdentity)
        peerManager.add(peer)
        return peer
    }

    @Test
    fun readyPears_broadcastFromUnsyncedAllowed_excludesPeerAwaitingChainIdentity() {
        peerManager.setAllowBroadcastFromUnsyncedPeers(true)
        addPeer(host = "1.1.1.1", ready = false, awaitingChainIdentity = true)
        val verified = addPeer(host = "2.2.2.2", ready = false, awaitingChainIdentity = false)

        assertEquals(listOf(verified), peerManager.readyPears())
    }

    @Test
    fun readyPears_broadcastFromUnsyncedNotAllowed_returnsOnlyReadyPeers() {
        peerManager.setAllowBroadcastFromUnsyncedPeers(false)
        addPeer(host = "1.1.1.1", ready = false)
        val ready = addPeer(host = "2.2.2.2", ready = true)

        assertEquals(listOf(ready), peerManager.readyPears())
    }
}

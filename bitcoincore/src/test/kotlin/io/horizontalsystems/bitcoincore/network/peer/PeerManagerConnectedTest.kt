package io.horizontalsystems.bitcoincore.network.peer

import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PeerManager.connected] is the broadcast/iteration list used by callers like
 * [io.horizontalsystems.bitcoincore.blocks.BloomFilterLoader.onFilterUpdated] —
 * they walk it to send messages to peers we trust. A peer still verifying its
 * chain identity has passed the version/verack handshake (its TCP socket is up
 * and [Peer.connected] is true), but we have not yet confirmed it is on our
 * chain. Letting it slip into the broadcast list means a Bitcoin Cash node
 * connected to the eCash kit (shared P2P magic + port) receives our bloom
 * filter and starts handing us BCH headers/merkle blocks before the chain
 * identity probe ever completes — exactly the bug we observed in logs.
 *
 * This invariant mirrors the one already enforced for [PeerManager.readyPears].
 */
class PeerManagerConnectedTest {

    private val peerManager = PeerManager()

    private fun addPeer(
        host: String,
        connected: Boolean = true,
        awaitingChainIdentity: Boolean = false,
    ): Peer {
        val peer = mock<Peer>()
        whenever(peer.host).thenReturn(host)
        whenever(peer.connected).thenReturn(connected)
        whenever(peer.awaitingChainIdentity).thenReturn(awaitingChainIdentity)
        peerManager.add(peer)
        return peer
    }

    @Test
    fun connected_excludesPeerAwaitingChainIdentity() {
        addPeer(host = "1.1.1.1", awaitingChainIdentity = true)
        val verified = addPeer(host = "2.2.2.2", awaitingChainIdentity = false)

        assertEquals(
            "Peers still inside the chain-identity probe must not appear in connected(); " +
                "broadcast paths (BloomFilterLoader.onFilterUpdated, etc.) iterate this list and " +
                "would otherwise send our bloom filter to a wrong-chain peer.",
            listOf(verified),
            peerManager.connected()
        )
    }

    @Test
    fun connected_excludesPeerWhoseSocketIsClosed() {
        addPeer(host = "1.1.1.1", connected = false)
        val live = addPeer(host = "2.2.2.2", connected = true)

        assertEquals(listOf(live), peerManager.connected())
    }
}

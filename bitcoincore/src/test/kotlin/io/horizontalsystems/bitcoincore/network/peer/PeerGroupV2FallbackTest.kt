package io.horizontalsystems.bitcoincore.network.peer

import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IPeerAddressManager
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import io.horizontalsystems.bitcoincore.network.transport.TransportException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A failed BIP324 handshake must never cost us the peer address.
 *
 * [PeerAddressManager.markFailed] deletes the host from storage, and most nodes on these networks
 * still predate BIP324 — PirateCash has three DNS seeds and Cosanta five, so treating "does not
 * speak v2" as a peer failure would burn the entire seed set within a few connection attempts and
 * leave the wallet unable to sync at all.
 */
class PeerGroupV2FallbackTest {

    private lateinit var hostManager: IPeerAddressManager
    private lateinit var peerGroup: PeerGroup

    @Before
    fun setup() {
        hostManager = mock()
        peerGroup = PeerGroup(
            hostManager,
            mock<Network> { on { logTag } doReturn "TestNetwork" },
            PeerManager(),
            10,
            mock<NetworkMessageParser>(),
            mock<NetworkMessageSerializer>(),
            mock<IConnectionManager>(),
            0,
            false
        )
    }

    private fun peer(host: String, generation: Int): Peer = mock {
        on { this.host } doReturn host
        on { this.generation } doReturn generation
    }

    private fun currentGeneration(): Int = peerGroup
        .javaClass
        .getDeclaredField("generation")
        .apply { isAccessible = true }
        .get(peerGroup)
        .let { (it as AtomicInteger).get() }

    @Suppress("UNCHECKED_CAST")
    private fun v1OnlyHosts(): Set<String> = peerGroup
        .javaClass
        .getDeclaredField("v1OnlyHosts")
        .apply { isAccessible = true }
        .get(peerGroup) as Set<String>

    @Test
    fun onDisconnect_handshakeFailed_marksHostV1OnlyAndKeepsTheAddress() {
        val peer = peer("1.2.3.4", currentGeneration())

        peerGroup.onDisconnect(peer, TransportException.HandshakeFailed("no v2 here"))

        assertTrue("the host must be remembered so the next attempt skips v2", "1.2.3.4" in v1OnlyHosts())
        verify(hostManager).markSuccess("1.2.3.4")
        verify(hostManager, never()).markFailed("1.2.3.4")
    }

    @Test
    fun onDisconnect_ordinaryFailure_stillMarksTheHostFailed() {
        val peer = peer("5.6.7.8", currentGeneration())

        peerGroup.onDisconnect(peer, Peer.Error("something else"))

        assertFalse("only handshake failures imply v1-only", "5.6.7.8" in v1OnlyHosts())
        verify(hostManager).markFailed("5.6.7.8")
    }

    @Test
    fun stop_clearsTheV1OnlyMemory() {
        peerGroup.onDisconnect(peer("1.2.3.4", currentGeneration()), TransportException.HandshakeFailed("no v2"))
        assertTrue("1.2.3.4" in v1OnlyHosts())

        peerGroup.stop()

        assertTrue("a node upgraded between runs deserves another v2 attempt", v1OnlyHosts().isEmpty())
    }

    /**
     * A handshake still unwinding from a previous lifecycle must not repopulate the set after
     * stop() cleared it, and must not be mistaken for a live peer failure — the exception it
     * carries was very likely caused by our own shutdown closing the socket.
     */
    @Test
    fun onDisconnect_staleGeneration_touchesNothingDestructive() {
        val stalePeer = peer("9.9.9.9", currentGeneration())
        peerGroup.stop()

        peerGroup.onDisconnect(stalePeer, TransportException.HandshakeFailed("late failure"))

        assertTrue("a stale peer must not repopulate the set", v1OnlyHosts().isEmpty())
        verify(hostManager, never()).markFailed("9.9.9.9")
        verify(hostManager).markSuccess("9.9.9.9")
    }

    @Test
    fun onDisconnect_staleGenerationWithOrdinaryError_doesNotDeleteTheAddress() {
        val stalePeer = peer("8.8.8.8", currentGeneration())
        peerGroup.stop()

        peerGroup.onDisconnect(stalePeer, Peer.Error("socket closed by our own shutdown"))

        verify(hostManager, never()).markFailed("8.8.8.8")
    }
}

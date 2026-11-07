package io.horizontalsystems.bitcoincore.network.peer

import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.GetHeadersMessage
import io.horizontalsystems.bitcoincore.network.messages.HeadersMessage
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import io.horizontalsystems.bitcoincore.network.messages.VerAckMessage
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.ExecutorService

class PeerChainIdentityTest {

    private val anchor = ByteArray(32) { 7 }
    private val network = mock<Network> {
        on { protocolVersion } doReturn 70015
        on { zeroHashBytes } doReturn ByteArray(32)
        on { logTag } doReturn "ECASH"
    }
    private val listener = mock<Peer.Listener>()
    private val parser = mock<NetworkMessageParser>()
    private val serializer = mock<NetworkMessageSerializer>()
    private val executor = mock<ExecutorService>()

    private fun header(previousHash: ByteArray) =
        BlockHeader(1, previousHash, ByteArray(32), 0L, 0L, 0L, ByteArray(32))

    private fun withPeer(
        anchorHash: ByteArray? = anchor,
        now: () -> Long = System::currentTimeMillis,
        block: (peer: Peer, connection: PeerConnection) -> Unit,
    ) {
        whenever(network.chainIdentityAnchorHash).thenReturn(anchorHash)
        Mockito.mockConstruction(PeerConnection::class.java).use { mocked ->
            val peer = Peer("host", network, listener, parser, serializer, executor, now)
            peer.start(executor)
            block(peer, mocked.constructed().first())
        }
    }

    @Test
    fun handleVerack_withAnchor_entersAwaitingAndSendsGetHeaders() = withPeer { peer, connection ->
        peer.onMessage(VerAckMessage())

        assertTrue(peer.awaitingChainIdentity)
        assertFalse(peer.ready)
        verify(connection).sendMessage(argThat {
            this is GetHeadersMessage && hashes.single().contentEquals(anchor)
        })
        verify(listener, never()).onConnect(any())
    }

    @Test
    fun handleVerack_noAnchor_connectsImmediately() = withPeer(anchorHash = null) { peer, _ ->
        peer.onMessage(VerAckMessage())

        assertFalse(peer.awaitingChainIdentity)
        assertTrue(peer.ready)
        verify(listener).onConnect(peer)
    }

    @Test
    fun chainIdentityHeaders_onAnchorChain_connects() = withPeer { peer, _ ->
        peer.onMessage(VerAckMessage())

        peer.onMessage(HeadersMessage(arrayOf(header(anchor))))

        assertFalse(peer.awaitingChainIdentity)
        assertTrue(peer.ready)
        verify(listener).onConnect(peer)
    }

    @Test
    fun chainIdentityHeaders_wrongChain_closesWithWrongChainAndDoesNotConnect() = withPeer { peer, connection ->
        peer.onMessage(VerAckMessage())

        peer.onMessage(HeadersMessage(arrayOf(header(ByteArray(32) { 1 }))))

        assertFalse(peer.ready)
        verify(connection).close(argThat { this is Peer.Error.WrongChain })
        verify(listener, never()).onConnect(any())
    }

    @Test
    fun chainIdentityHeaders_emptyHeaders_rejected() = withPeer { peer, connection ->
        peer.onMessage(VerAckMessage())

        peer.onMessage(HeadersMessage(emptyArray()))

        assertFalse(peer.ready)
        verify(connection).close(argThat { this is Peer.Error.WrongChain })
        verify(listener, never()).onConnect(any())
    }

    @Test
    fun onTimePeriodPassed_chainIdentityTimedOut_closesWithWrongChainError() {
        // A peer that never answers our chain-identity GetHeaders probe must
        // be marked as failed (not as success) so PeerAddressManager.markFailed
        // removes its address from storage. Closing with `null` puts the peer
        // through the markSuccess branch in PeerGroup.onDisconnect, the same
        // address gets re-elected on the next connectPeersIfRequired(), and
        // we end up in a silent-peer reconnect loop — exactly what we
        // observed for BCH nodes on the eCash port.
        var currentTime = 1_000_000L
        withPeer(now = { currentTime }) { peer, connection ->
            peer.onMessage(VerAckMessage())

            currentTime += 11_000
            peer.onTimePeriodPassed()

            verify(connection).close(argThat { this is Peer.Error.WrongChain })
        }
    }
}

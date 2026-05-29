package io.horizontalsystems.bitcoincore.network.peer

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doThrow
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * After PeerGroup.stop() shuts the sending executor down, any late sendMessage call
 * (e.g. a Pong issued from an in-flight onMessage callback) must NOT overwrite the
 * peer's intentional null disconnect cause with a synthetic error. If it does,
 * PeerGroup.onDisconnect treats the peer as failed and PeerAddressManager.markFailed
 * deletes the host from storage — devastating for networks with only a handful of
 * seed nodes.
 */
class PeerConnectionRejectedSendTest {

    @Test
    fun sendMessage_executorAlreadyShutDown_doesNotOverwriteNullDisconnectCause() {
        val sendingExecutor = mock<ExecutorService>()
        whenever(sendingExecutor.execute(any())).doThrow(RejectedExecutionException("pool shut down"))

        val peerConnection = PeerConnection(
            host = "1.2.3.4",
            network = mock<Network>(),
            listener = mock<PeerConnection.Listener>(),
            sendingExecutor = sendingExecutor,
            networkMessageParser = mock<NetworkMessageParser>(),
            networkMessageSerializer = mock<NetworkMessageSerializer>(),
        )

        // PeerGroup.stop() → peerManager.disconnectAll() → peer.close(null) happens first
        peerConnection.close(null)

        // …then a queued onMessage callback fires sendMessage AFTER the sending
        // executor was shut down by the same stop() — pool is already gone.
        peerConnection.sendMessage(mock<IMessage>())

        val disconnectError = peerConnection
            .javaClass
            .getDeclaredField("disconnectError")
            .apply { isAccessible = true }
            .get(peerConnection)

        assertNull(
            "Rejection from a shut-down sending executor must not turn a clean disconnect " +
                "into a failed one — PeerAddressManager.markFailed would delete the peer " +
                "address from storage and burn the seed host.",
            disconnectError
        )
    }
}

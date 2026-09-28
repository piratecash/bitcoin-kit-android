package io.horizontalsystems.bitcoincore.network.peer

import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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
            network = mock<Network> { on { logTag } doReturn "test" },
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

        // A closed connection now drops the message before it ever reaches the executor, which is
        // strictly stronger than surviving the rejection. Asserted explicitly, because otherwise
        // this test would pass without the rejection path running at all — the rejection path
        // itself is covered by PeerConnectionSendOrderTest against an open connection.
        verify(sendingExecutor, never()).execute(any())

        // The cause now lives in a terminal CloseState rather than a nullable field, so that a
        // clean close(null) stays distinguishable from "still open" and cannot be overwritten.
        val closeState = peerConnection
            .javaClass
            .getDeclaredField("closeState")
            .apply { isAccessible = true }
            .get(peerConnection)
            .let { (it as java.util.concurrent.atomic.AtomicReference<*>).get() }

        val disconnectError = closeState
            ?.javaClass
            ?.methods
            ?.firstOrNull { it.name == "getError" }
            ?.invoke(closeState)

        assertNull(
            "Rejection from a shut-down sending executor must not turn a clean disconnect " +
                "into a failed one — PeerAddressManager.markFailed would delete the peer " +
                "address from storage and burn the seed host.",
            disconnectError
        )
    }
}

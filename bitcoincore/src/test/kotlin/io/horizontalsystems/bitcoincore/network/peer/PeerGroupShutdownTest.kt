package io.horizontalsystems.bitcoincore.network.peer

import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IPeerAddressManager
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ExecutorService

/**
 * Pinpoints the thread-pool leak in [PeerGroup.stop]. Each kit restart creates a fresh
 * [PeerGroup] with two unbounded `newCachedThreadPool` instances; `stop()` only marks peer
 * connections for shutdown but never releases the pools. Across many restarts the orphaned
 * pools keep peer worker threads alive, which on memory-constrained devices manifests as
 * SIGABRT in dependent native libraries (e.g. mwebd / libgojni.so).
 */
class PeerGroupShutdownTest {

    private lateinit var peerGroup: PeerGroup

    @Before
    fun setup() {
        peerGroup = PeerGroup(
            mock<IPeerAddressManager>(),
            mock { on { logTag } doReturn "TestNetwork" },
            PeerManager(),
            10,
            mock<NetworkMessageParser>(),
            mock<NetworkMessageSerializer>(),
            mock<IConnectionManager>(),
            0,
            false
        )
    }

    @Test
    fun stop_shutsDownPeerThreadPool() {
        val peerThreadPool = peerGroup.readPool("peerThreadPool")

        peerGroup.stop()

        assertTrue(
            "peerThreadPool must be shut down after stop() — otherwise peer worker threads " +
                "accumulate across kit restarts and exhaust the address space on long sessions.",
            peerThreadPool.isShutdown
        )
    }

    @Test
    fun stop_shutsDownExecutorService() {
        val executorService = peerGroup.readPool("executorService")

        peerGroup.stop()

        assertTrue(
            "executorService must be shut down after stop() — otherwise sending-executor " +
                "threads accumulate across kit restarts.",
            executorService.isShutdown
        )
    }

    @Test
    fun start_afterStop_recreatesPools() {
        val peerThreadPoolBefore = peerGroup.readPool("peerThreadPool")
        val executorServiceBefore = peerGroup.readPool("executorService")

        peerGroup.stop()
        peerGroup.start()

        val peerThreadPoolAfter = peerGroup.readPool("peerThreadPool")
        val executorServiceAfter = peerGroup.readPool("executorService")

        assertFalse(
            "peerThreadPool must be live (not shut down) after restart — otherwise " +
                "subsequent connectPeersIfRequired() will throw RejectedExecutionException.",
            peerThreadPoolAfter.isShutdown
        )
        assertFalse(
            "executorService must be live (not shut down) after restart — otherwise " +
                "PeerConnection.sendMessage will throw RejectedExecutionException.",
            executorServiceAfter.isShutdown
        )
        assertNotSame(
            "Restart must create a fresh peerThreadPool instance.",
            peerThreadPoolBefore,
            peerThreadPoolAfter
        )
        assertNotSame(
            "Restart must create a fresh executorService instance.",
            executorServiceBefore,
            executorServiceAfter
        )
    }

    private fun PeerGroup.readPool(fieldName: String): ExecutorService =
        javaClass.getDeclaredField(fieldName)
            .apply { isAccessible = true }
            .get(this) as ExecutorService
}

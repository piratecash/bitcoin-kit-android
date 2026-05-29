package io.horizontalsystems.bitcoincore.network.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BitcoinCore.stop() invokes [closePeerScopedResources] on the kit's
 * registered [PeerGroup.Listener] collection so that per-kit resources (the
 * peersQueue inside InitialBlockDownload / BlockDownload / MasternodeListSyncer)
 * are released even when the surrounding PeerGroup is a [SharedPeerGroup] and
 * super.stop() does not run (refcount > 0).
 */
class PeerScopedResourcesTest {

    @Test
    fun closesAutoCloseableListeners() {
        var closed = 0
        val listener = object : PeerGroup.Listener, AutoCloseable {
            override fun close() { closed++ }
        }

        closePeerScopedResources(listOf(listener))

        assertEquals(1, closed)
    }

    @Test
    fun ignoresListenersThatAreNotAutoCloseable() {
        val listener = object : PeerGroup.Listener {}

        // Should not throw.
        closePeerScopedResources(listOf(listener))
    }

    @Test
    fun continuesClosingOtherListenersWhenOneThrows() {
        val failing = object : PeerGroup.Listener, AutoCloseable {
            override fun close(): Unit = throw RuntimeException("boom")
        }
        var afterClosed = false
        val healthy = object : PeerGroup.Listener, AutoCloseable {
            override fun close() { afterClosed = true }
        }

        closePeerScopedResources(listOf(failing, healthy))

        assertTrue(
            "A throwing close() must not stop the rest of the chain from being released.",
            afterClosed
        )
    }
}

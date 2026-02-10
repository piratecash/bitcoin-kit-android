package io.horizontalsystems.bitcoincore.network.peer

import com.nhaarman.mockitokotlin2.mock
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IPeerAddressManager
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedPeerGroupTest {

    private lateinit var sharedPeerGroup: SharedPeerGroup

    @Before
    fun setup() {
        sharedPeerGroup = SharedPeerGroup(
            mock<IPeerAddressManager>(),
            mock<Network>(),
            mock<PeerManager>(),
            10,
            mock<NetworkMessageParser>(),
            mock<NetworkMessageSerializer>(),
            mock<IConnectionManager>(),
            0,
            false
        )
    }

    @Test
    fun `start called once actually starts`() {
        sharedPeerGroup.start()
        assertTrue(sharedPeerGroup.running)
    }

    @Test
    fun `start called twice only starts once`() {
        sharedPeerGroup.start()
        sharedPeerGroup.start()
        assertTrue(sharedPeerGroup.running)
    }

    @Test
    fun `stop after two starts does NOT stop`() {
        sharedPeerGroup.start()
        sharedPeerGroup.start()
        sharedPeerGroup.stop()
        assertTrue(sharedPeerGroup.running)
    }

    @Test
    fun `stop twice after two starts actually stops`() {
        sharedPeerGroup.start()
        sharedPeerGroup.start()
        sharedPeerGroup.stop()
        sharedPeerGroup.stop()
        assertFalse(sharedPeerGroup.running)
    }

    @Test
    fun `stop more times than start - counter does not go negative`() {
        sharedPeerGroup.start()
        sharedPeerGroup.stop()
        sharedPeerGroup.stop()
        assertFalse(sharedPeerGroup.running)

        // After over-stopping, a single start() should restart cleanly
        sharedPeerGroup.start()
        assertTrue(sharedPeerGroup.running)
    }

    @Test
    fun `start after full stop cycle restarts cleanly`() {
        sharedPeerGroup.start()
        sharedPeerGroup.stop()
        assertFalse(sharedPeerGroup.running)

        sharedPeerGroup.start()
        assertTrue(sharedPeerGroup.running)
    }

    @Test
    fun `stop without any start does not crash`() {
        sharedPeerGroup.stop()
        assertFalse(sharedPeerGroup.running)
    }
}

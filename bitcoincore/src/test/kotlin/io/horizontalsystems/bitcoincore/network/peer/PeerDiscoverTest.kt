package io.horizontalsystems.bitcoincore.network.peer

import io.horizontalsystems.bitcoincore.core.IPeerAddressManager
import io.horizontalsystems.bitcoincore.core.IPeerAddressManagerListener
import io.horizontalsystems.bitcoincore.utils.FakeSocksServer
import io.horizontalsystems.bitcoincore.utils.clearSocksProxy
import io.horizontalsystems.bitcoincore.utils.closedPort
import io.horizontalsystems.bitcoincore.utils.setSocksProxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.after
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PeerDiscoverTest {

    private val addressListener = mock<IPeerAddressManagerListener>()
    private val peerAddressManager = mock<IPeerAddressManager> { on { listener } doReturn addressListener }
    private val peerDiscover = PeerDiscover(peerAddressManager, "Test")

    @After
    fun tearDown() {
        clearSocksProxy()
    }

    @Test
    fun lookup_socksProxySet_resolvesSeedThroughProxy() {
        FakeSocksServer(FakeSocksServer.ipv4Reply(203, 0, 113, 7)).use { server ->
            server.useAsSystemProxy()

            // Not resolvable locally: only the proxy can produce this address.
            peerDiscover.lookup(listOf(SEED))

            verify(peerAddressManager, timeout(WAIT_MILLIS)).addIps(SEED, listOf("203.0.113.7"))
            verify(peerAddressManager, never()).addUnreachedHosts(any())
        }
    }

    @Test
    fun lookup_hostNotFoundByProxy_marksHostUnreached() {
        FakeSocksServer(FakeSocksServer.HOST_UNREACHABLE_REPLY).use { server ->
            server.useAsSystemProxy()

            peerDiscover.lookup(listOf(SEED))

            verify(peerAddressManager, timeout(WAIT_MILLIS)).addUnreachedHosts(SEED)
            verify(peerAddressManager, never()).addIps(any(), any())
        }
    }

    @Test
    fun lookup_proxyRefused_retriesWithoutMarkingHostUnreached() {
        setSocksProxy("127.0.0.1", closedPort().toString())

        peerDiscover.lookup(listOf(SEED))

        verify(addressListener, timeout(RETRY_WAIT_MILLIS)).onAddAddress()
        verify(peerAddressManager, never()).addUnreachedHosts(any())
        verify(peerAddressManager, never()).addIps(any(), any())
    }

    @Test
    fun lookup_halfProxyConfig_skipsLookupAndRetries() {
        System.setProperty("socksProxyHost", "127.0.0.1")

        peerDiscover.lookup(listOf(SEED))

        verify(addressListener, timeout(RETRY_WAIT_MILLIS)).onAddAddress()
        verify(peerAddressManager, never()).addUnreachedHosts(any())
        verify(peerAddressManager, never()).addIps(any(), any())
    }

    @Test
    fun lookup_afterSuccessfulLookup_runsAgain() {
        FakeSocksServer(FakeSocksServer.ipv4Reply(203, 0, 113, 7)).use { server ->
            server.useAsSystemProxy()
            peerDiscover.lookup(listOf(SEED))
            verify(peerAddressManager, timeout(WAIT_MILLIS)).addIps(SEED, listOf("203.0.113.7"))

            // The finished batch releases its flag right after addIps; wait for that
            verify(peerAddressManager, after(500).times(1)).addIps(any(), any())
            peerDiscover.lookup(listOf(SEED))

            verify(peerAddressManager, timeout(WAIT_MILLIS).times(2)).addIps(SEED, listOf("203.0.113.7"))
            assertEquals(listOf(SEED, SEED), server.resolvedHosts)
        }
    }

    @Test
    fun lookup_requestedDuringSuccessfulLookup_notifiesListenerAfterBatch() {
        val reentrantManager = mock<IPeerAddressManager> { on { listener } doReturn addressListener }
        val discover = PeerDiscover(reentrantManager, "Test")
        // Real addIps notifies the peer group synchronously, which may ask for a lookup mid-batch
        whenever(reentrantManager.addIps(any(), any())).thenAnswer { discover.lookup(listOf(SEED)) }
        FakeSocksServer(FakeSocksServer.ipv4Reply(203, 0, 113, 7)).use { server ->
            server.useAsSystemProxy()

            discover.lookup(listOf(SEED))

            verify(addressListener, timeout(WAIT_MILLIS)).onAddAddress()
            assertEquals(listOf(SEED), server.resolvedHosts)
        }
    }

    @Test
    fun lookup_soonAfterFailedLookup_isIgnored() {
        FakeSocksServer(FakeSocksServer.HOST_UNREACHABLE_REPLY).use { server ->
            server.useAsSystemProxy()
            peerDiscover.lookup(listOf(SEED))
            verify(peerAddressManager, timeout(WAIT_MILLIS)).addUnreachedHosts(SEED)

            peerDiscover.lookup(listOf(SEED))

            verify(peerAddressManager, after(500).times(1)).addUnreachedHosts(any())
            assertEquals(listOf(SEED), server.resolvedHosts)
        }
    }

    private companion object {
        const val SEED = "seed.invalid"
        const val WAIT_MILLIS = 5_000L
        const val RETRY_WAIT_MILLIS = 10_000L
    }
}

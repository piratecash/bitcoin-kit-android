package io.horizontalsystems.bitcoincore.managers

import io.horizontalsystems.bitcoincore.network.BitcoinNetworkError
import io.horizontalsystems.bitcoincore.network.BitcoinNetworkErrorListener
import io.horizontalsystems.bitcoincore.network.NetworkErrorListenerHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiManagerNetworkErrorTest {

    // RFC 2606 reserved TLD: guaranteed never to resolve, so the request fails
    // fast with UnknownHostException without touching a real network.
    private val unreachableHost = "https://nonexistent.invalid"

    @Test
    fun get_transportFailure_emitsNetworkErrorWithMethodUrlAndHost() {
        var captured: BitcoinNetworkError? = null
        val holder = NetworkErrorListenerHolder().apply {
            listener = BitcoinNetworkErrorListener { captured = it }
        }
        val apiManager = ApiManager(unreachableHost, holder)

        assertThrows(ApiManagerException::class.java) { apiManager.get("addresses") }

        val error = requireNotNull(captured)
        assertEquals("GET", error.method)
        assertEquals("$unreachableHost/addresses", error.url)
        assertEquals("nonexistent.invalid", error.host)
        assertTrue(error.resolvedIps.isEmpty())
    }

    @Test
    fun get_throwingListener_stillThrowsApiManagerException() {
        val holder = NetworkErrorListenerHolder().apply {
            listener = BitcoinNetworkErrorListener { throw RuntimeException("listener boom") }
        }
        val apiManager = ApiManager(unreachableHost, holder)

        // The observer throwing must not replace or suppress the original failure.
        assertThrows(ApiManagerException::class.java) { apiManager.get("addresses") }
    }

    @Test
    fun get_noListener_stillThrowsApiManagerException() {
        val apiManager = ApiManager(unreachableHost)

        assertThrows(ApiManagerException::class.java) { apiManager.get("addresses") }
    }
}

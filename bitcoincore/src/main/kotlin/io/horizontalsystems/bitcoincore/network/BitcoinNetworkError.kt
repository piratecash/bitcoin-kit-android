package io.horizontalsystems.bitcoincore.network

import java.net.InetAddress
import java.net.URL

fun interface BitcoinNetworkErrorListener {
    fun onNetworkError(error: BitcoinNetworkError)
}

data class BitcoinNetworkError(
    val source: String,
    val method: String,
    val url: String,
    val host: String,
    val resolvedIps: List<String>,
    val throwable: Throwable
)

/**
 * Mutable, thread-safe holder that lets the app install a passive network-error
 * observer AFTER the kit and its API providers are already constructed.
 *
 * Passive-observer contract:
 * - with no listener installed [emit] does nothing — it builds no error object and
 *   performs no DNS resolution (early return on null listener);
 * - emission never propagates a failure to the caller: any Throwable from the
 *   listener or from best-effort field construction is swallowed, so diagnostics
 *   can never break the network error path they observe.
 */
class NetworkErrorListenerHolder {

    @Volatile
    var listener: BitcoinNetworkErrorListener? = null

    fun emit(source: String, method: String, url: String, throwable: Throwable) {
        val listener = this.listener ?: return
        try {
            val host = hostOf(url)
            listener.onNetworkError(
                BitcoinNetworkError(
                    source = source,
                    method = method,
                    url = url,
                    host = host,
                    resolvedIps = resolveHostAddresses(host),
                    throwable = throwable
                )
            )
        } catch (_: Throwable) {
            // Diagnostics must never break the caller's error path.
        }
    }

    private fun hostOf(url: String): String = try {
        URL(url).host.orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun resolveHostAddresses(host: String): List<String> {
        if (host.isBlank()) return emptyList()
        return try {
            InetAddress.getAllByName(host)
                .mapNotNull { it.hostAddress }
                .distinct()
        } catch (_: Throwable) {
            emptyList()
        }
    }
}

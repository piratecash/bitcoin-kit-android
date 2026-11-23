package io.horizontalsystems.bitcoincore.network.peer

import io.horizontalsystems.bitcoincore.core.IPeerAddressManager
import io.horizontalsystems.bitcoincore.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

class PeerDiscover(private val peerAddressManager: IPeerAddressManager, private val tag: String) {

    private val logger = Logger.getLogger("PeerDiscover")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lookupInProgress = AtomicBoolean(false)

    @Volatile
    private var retryAfterNanos = System.nanoTime()

    fun lookup(dnsList: List<String>) {
        // Added addresses make the peer group ask again, which lands here while no peer is free
        if (!lookupInProgress.compareAndSet(false, true)) return
        if (System.nanoTime() - retryAfterNanos < 0) {
            lookupInProgress.set(false)
            return
        }

        logger.info("$tag: Lookup peers from DNS seed...")

        scope.launch {
            val found = try {
                lookupSeeds(dnsList)
            } catch (e: Exception) {
                logger.warning("$tag: DNS seed lookup failed: ${e.message}")
                false
            }
            // Only a failed lookup is throttled; its delayed wakeup below covers requests dropped meanwhile
            if (!found) retryAfterNanos = System.nanoTime() + RETRY_INTERVAL_MILLIS * 1_000_000
            lookupInProgress.set(false)

            // Requests dropped while this batch ran get no other answer: always ask the peer group again
            if (!found) delay(RETRY_INTERVAL_MILLIS)
            peerAddressManager.listener?.onAddAddress()
        }
    }

    private suspend fun lookupSeeds(dnsList: List<String>): Boolean {
        // A broken proxy setting must not fall back to local DNS; the retry covers it
        val proxy = try {
            NetworkUtils.requireSocksProxyAddress()
        } catch (e: IOException) {
            logger.warning("$tag: Skip DNS seed lookup: ${e.message}")
            return false
        }

        // In parallel: through Tor each seed can take up to the SOCKS timeout
        return coroutineScope {
            dnsList.map { host -> async { lookupHost(host, proxy) } }.awaitAll().any { it }
        }
    }

    private fun lookupHost(host: String, proxy: InetSocketAddress?): Boolean {
        val ips = try {
            resolve(host, proxy)
        } catch (e: IOException) {
            logger.warning("$tag: Cannot reach SOCKS proxy to look up host: $host, ${e.message}")
            return false
        }

        if (ips == null) {
            peerAddressManager.addUnreachedHosts(host)
            logger.warning("$tag: Cannot look up host: $host")
            return false
        }

        logger.info("$tag: Fetched ${ips.size} peer addresses from host: $host")
        peerAddressManager.addIps(host, ips)
        return ips.isNotEmpty()
    }

    /** Null when the host does not resolve; throws IOException when the proxy cannot be used. */
    private fun resolve(host: String, proxy: InetSocketAddress?): List<String>? {
        if (proxy != null) {
            return NetworkUtils.resolveThroughSocks(host, proxy)?.let { listOf(it) }
        }

        return try {
            InetAddress.getAllByName(host)
                .filter { it !is Inet6Address }
                .mapNotNull { it.hostAddress }
        } catch (e: UnknownHostException) {
            null
        }
    }

    companion object {
        private const val RETRY_INTERVAL_MILLIS = 5_000L
    }
}

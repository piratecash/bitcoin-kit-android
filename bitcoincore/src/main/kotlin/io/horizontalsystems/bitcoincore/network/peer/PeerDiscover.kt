package io.horizontalsystems.bitcoincore.network.peer

import io.horizontalsystems.bitcoincore.core.IPeerAddressManager
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.logging.Logger

class PeerDiscover(private val peerAddressManager: IPeerAddressManager, private val tag: String) {

    private val logger = Logger.getLogger("PeerDiscover")

    fun lookup(dnsList: List<String>) {
        logger.info("Lookup peers from DNS seed...")

        // todo: launch coroutines for each dns resolve
        GlobalScope.launch {
            dnsList.forEach { host ->
                try {
                    val ips = InetAddress
                        .getAllByName(host)
                        .filter { it !is Inet6Address }
                        .map { it.hostAddress }

                    logger.info("$tag: Fetched ${ips.size} peer addresses from host: $host")
                    peerAddressManager.addIps(ips)
                } catch (e: UnknownHostException) {
                    logger.warning("$tag: Cannot look up host: $host")
                }
            }
        }
    }
}

package io.horizontalsystems.bitcoincore.utils

import io.horizontalsystems.bitcoincore.extensions.toHexString
import java.io.DataInputStream
import java.io.IOException
import java.net.*

object NetworkUtils {

    fun getLocalInetAddress(): InetAddress {
        try {
            return InetAddress.getLocalHost()
        } catch (e: UnknownHostException) {
            throw RuntimeException(e)
        }
    }

    fun getIPv6(inetAddr: InetAddress): ByteArray {
        val ip = inetAddr.address
        if (ip.size == 16) {
            return ip
        }

        if (ip.size == 4) {
            val ipv6 = ByteArray(16)
            ipv6[10] = -1
            ipv6[11] = -1
            System.arraycopy(ip, 0, ipv6, 12, 4)
            return ipv6
        }

        throw RuntimeException("Bad IP: " + ip.toHexString())
    }

    fun socksProxyAddress(): InetSocketAddress? = try {
        requireSocksProxyAddress()
    } catch (e: IOException) {
        null
    }

    /** Null when no SOCKS proxy is set; throws when it is set but incomplete or invalid. */
    fun requireSocksProxyAddress(): InetSocketAddress? {
        val host = System.getProperty("socksProxyHost")
        val portValue = System.getProperty("socksProxyPort")
        if (host == null && portValue == null) return null

        val port = portValue?.toIntOrNull()?.takeIf { it in 0..65535 }
        if (host == null || port == null) {
            throw IOException("Invalid SOCKS proxy configuration: host=$host, port=$portValue")
        }
        return InetSocketAddress.createUnresolved(host, port)
    }

    fun createSocket(): Socket {
        val socketAddress = socksProxyAddress()

        return if (socketAddress != null) {
            Socket(Proxy(Proxy.Type.SOCKS, socketAddress))
        } else {
            Socket()
        }
    }

    /**
     * Resolves [host] to one IPv4 address through Tor's SOCKS RESOLVE extension (0xF0), bypassing
     * local DNS. Null when the proxy cannot resolve it or answers with a non-IPv4 address.
     */
    fun resolveThroughSocks(host: String, proxy: InetSocketAddress, timeoutMillis: Int = 30_000): String? {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        if (hostBytes.size > 255) return null

        Socket().use { socket ->
            socket.connect(InetSocketAddress(proxy.hostString, proxy.port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            val output = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            output.write(byteArrayOf(SOCKS_VERSION, 0x01, 0x00))
            output.flush()
            val greeting = ByteArray(2).also { input.readFully(it) }
            if (greeting[0] != SOCKS_VERSION || greeting[1] != 0x00.toByte()) {
                throw IOException("SOCKS proxy refused the no-auth greeting")
            }

            output.write(byteArrayOf(SOCKS_VERSION, SOCKS_RESOLVE, 0x00, 0x03, hostBytes.size.toByte()) + hostBytes + byteArrayOf(0x00, 0x00))
            output.flush()

            val header = ByteArray(4).also { input.readFully(it) }
            if (header[1] != 0x00.toByte() || header[3] != ADDRESS_TYPE_IPV4) return null

            val address = ByteArray(4).also { input.readFully(it) }
            return InetAddress.getByAddress(address).hostAddress
        }
    }

    private const val SOCKS_VERSION: Byte = 0x05
    private const val SOCKS_RESOLVE: Byte = 0xF0.toByte()
    private const val ADDRESS_TYPE_IPV4: Byte = 0x01

}

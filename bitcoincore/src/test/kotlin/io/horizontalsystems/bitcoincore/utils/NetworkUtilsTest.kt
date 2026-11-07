package io.horizontalsystems.bitcoincore.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class NetworkUtilsTest {

    @After
    fun tearDown() {
        clearSocksProxy()
    }

    @Test
    fun resolveThroughSocks_ipv4Reply_returnsAddressFromProxy() {
        FakeSocksServer(FakeSocksServer.ipv4Reply(203, 0, 113, 7)).use { server ->
            assertEquals("203.0.113.7", NetworkUtils.resolveThroughSocks("seed.example", server.address))
            assertEquals(listOf("seed.example"), server.resolvedHosts)
        }
    }

    @Test
    fun resolveThroughSocks_errorReply_returnsNull() {
        FakeSocksServer(FakeSocksServer.HOST_UNREACHABLE_REPLY).use { server ->
            assertNull(NetworkUtils.resolveThroughSocks("seed.example", server.address))
        }
    }

    @Test
    fun resolveThroughSocks_ipv6Reply_returnsNull() {
        FakeSocksServer(FakeSocksServer.IPV6_REPLY).use { server ->
            assertNull(NetworkUtils.resolveThroughSocks("seed.example", server.address))
        }
    }

    @Test
    fun resolveThroughSocks_proxyRefused_throwsIOException() {
        val proxy = InetSocketAddress.createUnresolved("127.0.0.1", closedPort())

        assertThrows(IOException::class.java) {
            NetworkUtils.resolveThroughSocks("seed.example", proxy, 2_000)
        }
    }

    @Test
    fun requireSocksProxyAddress_onlyHostSet_throwsIOException() {
        System.setProperty("socksProxyHost", "127.0.0.1")

        assertThrows(IOException::class.java) { NetworkUtils.requireSocksProxyAddress() }
    }

    @Test
    fun requireSocksProxyAddress_portNotNumber_throwsIOException() {
        setSocksProxy("127.0.0.1", "tor")

        assertThrows(IOException::class.java) { NetworkUtils.requireSocksProxyAddress() }
    }

    @Test
    fun requireSocksProxyAddress_noProxy_returnsNull() {
        assertNull(NetworkUtils.requireSocksProxyAddress())
    }
}

internal fun setSocksProxy(host: String, port: String) {
    System.setProperty("socksProxyHost", host)
    System.setProperty("socksProxyPort", port)
}

internal fun clearSocksProxy() {
    System.clearProperty("socksProxyHost")
    System.clearProperty("socksProxyPort")
}

internal fun closedPort(): Int = ServerSocket(0).use { it.localPort }

/** Loopback SOCKS5 server that answers Tor's RESOLVE command with [reply]. */
internal class FakeSocksServer(private val reply: ByteArray) : Closeable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val resolvedHosts = CopyOnWriteArrayList<String>()
    val port: Int get() = serverSocket.localPort
    val address: InetSocketAddress get() = InetSocketAddress.createUnresolved("127.0.0.1", port)

    init {
        thread(isDaemon = true) {
            while (!serverSocket.isClosed) {
                try {
                    serverSocket.accept().use(::answer)
                } catch (e: IOException) {
                    // Server closed or the client went away
                }
            }
        }
    }

    private fun answer(socket: Socket) {
        socket.soTimeout = 2_000
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()

        input.readFully(ByteArray(3))
        output.write(byteArrayOf(0x05, 0x00))
        val request = ByteArray(5).also { input.readFully(it) }
        val host = ByteArray(request[4].toInt() and 0xFF).also { input.readFully(it) }
        input.readFully(ByteArray(2))

        if (request[1] == 0xF0.toByte() && request[3] == 0x03.toByte()) {
            resolvedHosts += String(host, Charsets.US_ASCII)
            output.write(reply)
        } else {
            output.write(COMMAND_NOT_SUPPORTED_REPLY)
        }
        output.flush()
    }

    fun useAsSystemProxy() = setSocksProxy("127.0.0.1", port.toString())

    override fun close() = serverSocket.close()

    companion object {
        val HOST_UNREACHABLE_REPLY = byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        val IPV6_REPLY = byteArrayOf(0x05, 0x00, 0x00, 0x04) + ByteArray(16) + byteArrayOf(0, 0)
        private val COMMAND_NOT_SUPPORTED_REPLY = byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0)

        fun ipv4Reply(vararg octets: Int) =
            byteArrayOf(0x05, 0x00, 0x00, 0x01) + octets.map { it.toByte() }.toByteArray() + byteArrayOf(0, 0)
    }
}

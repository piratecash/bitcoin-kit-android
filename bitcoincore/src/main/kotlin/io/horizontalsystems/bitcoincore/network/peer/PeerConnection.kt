package io.horizontalsystems.bitcoincore.network.peer

import co.touchlab.kermit.Logger as KermitLogger
import io.horizontalsystems.bitcoincore.io.BitcoinInput
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import io.horizontalsystems.bitcoincore.network.transport.DefaultTransportFactory
import io.horizontalsystems.bitcoincore.network.transport.IPeerTransport
import io.horizontalsystems.bitcoincore.network.transport.ITransportFactory
import io.horizontalsystems.bitcoincore.network.transport.MessagePayloadException
import io.horizontalsystems.bitcoincore.network.transport.SocketDeadlineReader
import io.horizontalsystems.bitcoincore.network.transport.TransportException
import io.horizontalsystems.bitcoincore.utils.NetworkUtils
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger
import kotlin.concurrent.withLock

class PeerConnection internal constructor(
    private val host: String,
    private val network: Network,
    private val listener: Listener,
    private val sendingExecutor: ExecutorService,
    private val useV2: Boolean,
    internal val generation: Int,
    transportFactory: ITransportFactory,
) : Runnable {
    private val log = KermitLogger.withTag(network.logTag)

    /** Public signature kept exactly as it was, so every existing call site compiles untouched. */
    constructor(
        host: String,
        network: Network,
        listener: Listener,
        sendingExecutor: ExecutorService,
        networkMessageParser: NetworkMessageParser,
        networkMessageSerializer: NetworkMessageSerializer,
    ) : this(
        host, network, listener, sendingExecutor,
        useV2 = false,
        generation = 0,
        transportFactory = DefaultTransportFactory(network, networkMessageParser, networkMessageSerializer),
    )

    interface Listener {
        fun socketConnected(address: InetAddress)
        fun disconnected(e: Exception? = null)
        fun onTimePeriodPassed() // didn't find better name
        fun onMessage(message: IMessage)
    }

    private sealed interface CloseState {
        data object Open : CloseState
        data class Closed(val error: Exception?) : CloseState
    }

    private val socket = NetworkUtils.createSocket()
    private val transport: IPeerTransport = transportFactory.create(useV2)

    private val logger = Logger.getLogger("Peer[$host]")
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    // A terminal state rather than a nullable error: `close(null)` is a legitimate clean shutdown,
    // and with a plain AtomicReference<Exception> it would be indistinguishable from "still open",
    // letting the SocketException our own socket.close() provokes overwrite it and turn a clean
    // disconnect into a peer failure — which deletes the address from storage.
    private val closeState = AtomicReference<CloseState>(CloseState.Open)

    // Messages are queued and drained by a single winner of the CAS below, so they are written in
    // submission order. A plain lock would prevent interleaving but not reordering: tasks on the
    // shared executor can acquire it in any order, which could put `verack` on the wire before
    // `version` and corrupt the v2 cipher's packet sequence.
    private val sendQueue = ConcurrentLinkedQueue<IMessage>()
    private val draining = AtomicBoolean(false)

    // Held for the duration of each write, and by teardown before it wipes cipher keys, so a wipe
    // can never land in the middle of an encryption.
    private val writeLock = ReentrantLock()

    private val isRunning: Boolean
        get() = closeState.get() is CloseState.Open

    override fun run() {
        try {
            socket.connect(InetSocketAddress(host, network.port), 10000)
            socket.soTimeout = 10000

            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            outputStream = output
            inputStream = input
            val bitcoinInput = BitcoinInput(input)

            // The handshake runs before the peer is announced, so `version` is only sent once the
            // channel is encrypted. Bounded by its own absolute deadline, not the socket timeout.
            transport.connect(SocketDeadlineReader(socket, input), output)
            socket.soTimeout = 10000

            logger.info("${network.logTag}: Socket $host connected (${if (transport.isEncrypted) "v2" else "v1"}).")

            listener.socketConnected(socket.inetAddress)

            while (isRunning) {
                listener.onTimePeriodPassed()

                Thread.sleep(1000)

                while (isRunning && input.available() > 0) {
                    receiveMessage(bitcoinInput)
                }
            }
        } catch (e: Exception) {
            close(e)
        } finally {
            teardown()
            listener.disconnected((closeState.get() as? CloseState.Closed)?.error)
        }
    }

    /**
     * v1 keeps its historical swallow-and-continue behaviour; a v2 framing failure must not, because
     * the ciphers have already consumed state and every later byte would be misinterpreted.
     */
    private fun receiveMessage(bitcoinInput: BitcoinInput) {
        try {
            val message = transport.readMessage(bitcoinInput) ?: return
            log.d { "<= $message" }
            listener.onMessage(message)
        } catch (e: TransportException) {
            close(e)
        } catch (e: MessagePayloadException) {
            // The packet was authenticated and fully consumed, so the stream is still usable.
            logger.warning("${network.logTag}: Failed to parse payload: ${e.message}")
        } catch (e: Exception) {
            e.printStackTrace()
            logger.warning("${network.logTag}: Failed to parse message: ${e.message}")
        }
    }

    /**
     * Runs on the peer thread once the receive loop has exited, and takes [writeLock] so an
     * in-flight write cannot have its cipher state wiped underneath it. Closing the socket first
     * makes that write fail fast instead of blocking teardown — sockets have no write timeout.
     */
    private fun teardown() {
        sendQueue.clear()
        writeLock.withLock { transport.close() }

        outputStream?.close()
        outputStream = null
        inputStream?.close()
        inputStream = null
    }

    fun close(error: Exception? = null) {
        if (!closeState.compareAndSet(CloseState.Open, CloseState.Closed(error))) return

        // Unblocks a handshake or read parked on the socket, so the connection winds down promptly
        // instead of waiting out its deadline.
        try {
            socket.close()
        } catch (e: Exception) {
            // Already closed or never connected; nothing useful to do.
        }
        sendQueue.clear()
    }

    fun sendMessage(message: IMessage) {
        if (!isRunning) return

        sendQueue.add(message)
        // Re-checked after enqueueing: close() may have cleared the queue in between, and a final
        // clear cannot remove something added after it ran.
        if (!isRunning) {
            sendQueue.remove(message)
            return
        }
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (!isRunning) return
        if (!draining.compareAndSet(false, true)) return

        try {
            sendingExecutor.execute(::drain)
        } catch (_: RejectedExecutionException) {
            // PeerGroup.stop() shut the sending executor down. By that point
            // peerManager.disconnectAll() has already called close(null) on
            // this connection, so we MUST NOT call close(e) here — that would
            // overwrite the clean (null) disconnect cause with a synthetic
            // error and make PeerGroup.onDisconnect mark the peer as failed.
            // PeerAddressManager.markFailed deletes the host from storage,
            // which on networks with only a few seed nodes
            // (Cosanta / Pirate Cash currently have 3) would burn valid peer
            // addresses on every kit restart. Late sends are safe to drop:
            // the peer is on its way out cleanly.
            draining.set(false)
            sendQueue.clear()
        }
    }

    private fun drain() {
        try {
            while (isRunning) {
                val message = sendQueue.poll() ?: break
                val output = outputStream ?: break

                writeLock.withLock {
                    // Authoritative inside the lock: teardown can only wipe while holding it, so a
                    // state read taken before acquiring it could already be stale.
                    if (!isRunning) return@withLock
                    logger.info("${network.logTag}: => $message")
                    transport.writeMessage(message, output)
                }
            }
        } catch (e: Exception) {
            close(e)
            sendQueue.clear()
        } finally {
            draining.set(false)
            if (isRunning && sendQueue.isNotEmpty()) {
                scheduleDrain()
            }
        }
    }
}

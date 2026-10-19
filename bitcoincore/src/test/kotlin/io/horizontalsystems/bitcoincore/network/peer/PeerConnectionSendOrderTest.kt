package io.horizontalsystems.bitcoincore.network.peer

import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import io.horizontalsystems.bitcoincore.io.BitcoinInput
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.PingMessage
import io.horizontalsystems.bitcoincore.network.transport.IDeadlineReader
import io.horizontalsystems.bitcoincore.network.transport.IPeerTransport
import io.horizontalsystems.bitcoincore.network.transport.ITransportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The send path has to be strictly ordered, not merely race-free.
 *
 * A lock alone would stop two writes interleaving but not stop them being *reordered*: tasks on the
 * shared executor can acquire it in any order, which for v2 corrupts the packet sequence (each
 * packet's nonce is derived from a counter) and for v1 can put `verack` on the wire before
 * `version`. These tests pin the ordering guarantee and the teardown interactions around it.
 */
class PeerConnectionSendOrderTest {

    private val network: Network = mock { on { logTag } doReturn "TestNetwork" }

    /** Records what reaches the wire, and can be paused to construct a precise interleaving. */
    private class RecordingTransport(
        private val onWrite: (IMessage) -> Unit = {},
    ) : IPeerTransport {
        val written: MutableList<IMessage> = Collections.synchronizedList(mutableListOf())
        val closed = AtomicBoolean(false)

        override val isEncrypted = false
        override fun connect(deadlineReader: IDeadlineReader, output: OutputStream) = Unit
        override fun readMessage(input: BitcoinInput): IMessage? = null
        override fun writeMessage(message: IMessage, output: OutputStream) {
            onWrite(message)
            written.add(message)
        }
        override fun close() {
            closed.set(true)
        }
    }

    private fun connection(
        transport: IPeerTransport,
        executor: ExecutorService,
    ): PeerConnection {
        val connection = PeerConnection(
            host = "1.2.3.4",
            network = network,
            listener = mock(),
            sendingExecutor = executor,
            useV2 = false,
            generation = 0,
            transportFactory = object : ITransportFactory {
                override fun create(useV2: Boolean) = transport
            },
        )
        // The drain writes only when an output stream exists; run() never happens in a unit test.
        connection.javaClass.getDeclaredField("outputStream").apply { isAccessible = true }
            .set(connection, OutputStream.nullOutputStream())
        return connection
    }

    @Test
    fun sendMessage_manyMessagesFromManyThreads_areWrittenInSubmissionOrder() {
        val transport = RecordingTransport()
        val executor = Executors.newFixedThreadPool(8)
        val connection = connection(transport, executor)

        // Submission order is defined by the order sendMessage returns, so submit from one thread
        // and let the pool race on draining — which is exactly the production shape.
        val messages = (1..500).map { PingMessage(it.toLong()) }
        messages.forEach(connection::sendMessage)

        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals("every message must reach the wire", messages.size, transport.written.size)
        assertEquals(
            "messages must be written in submission order",
            messages.map { it.nonce },
            transport.written.map { (it as PingMessage).nonce },
        )
    }

    @Test
    fun sendMessage_afterClose_isDropped() {
        val transport = RecordingTransport()
        val executor = Executors.newSingleThreadExecutor()
        val connection = connection(transport, executor)

        connection.close(null)
        connection.sendMessage(PingMessage(1))

        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue("a closed connection must not write", transport.written.isEmpty())
    }

    /**
     * The interleaving a final `clear()` cannot cover: the sender observes an open connection, is
     * descheduled, close() runs and clears the queue, and only then does the enqueue land.
     */
    @Test
    fun sendMessage_racingWithClose_leavesNothingQueuedOrWritten() {
        val transport = RecordingTransport()
        val executor = Executors.newSingleThreadExecutor()
        val connection = connection(transport, executor)

        val enqueued = CountDownLatch(1)
        val closeDone = CountDownLatch(1)
        val sender = Thread {
            enqueued.countDown()
            closeDone.await()
            connection.sendMessage(PingMessage(7))
        }
        sender.start()
        enqueued.await()
        connection.close(null)
        closeDone.countDown()
        sender.join(5_000)

        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue("nothing may be written after close", transport.written.isEmpty())
        assertTrue("nothing may be retained on a closed connection", queue(connection).isEmpty())
    }

    @Test
    fun drain_writeFailure_closesOnceAndStopsRescheduling() {
        val failing = RecordingTransport(onWrite = { throw IllegalStateException("socket died") })
        val executor = Executors.newSingleThreadExecutor()
        val connection = connection(failing, executor)

        connection.sendMessage(PingMessage(1))
        connection.sendMessage(PingMessage(2))

        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        assertFalse("a failed write must close the connection", isOpen(connection))
        assertTrue("the queue must not keep messages against a dead socket", queue(connection).isEmpty())
        assertFalse("the drain guard must be released", draining(connection))
    }

    @Test
    fun scheduleDrain_rejectedByShutDownExecutor_releasesTheGuardAndKeepsTheCleanCause() {
        val transport = RecordingTransport()
        val executor = Executors.newSingleThreadExecutor().apply { shutdownNow() }
        val connection = connection(transport, executor)

        // Not closed first, so the rejection path is genuinely exercised — closing beforehand would
        // make sendMessage return early and never touch the executor at all.
        connection.sendMessage(PingMessage(1))

        assertFalse("the drain guard must not leak on rejection", draining(connection))
        assertTrue("the queue must not retain the dropped message", queue(connection).isEmpty())
        assertTrue("a rejected send must not close the connection", isOpen(connection))
    }

    private fun isOpen(connection: PeerConnection): Boolean = connection.javaClass
        .getDeclaredField("closeState").apply { isAccessible = true }
        .get(connection)
        .let { (it as java.util.concurrent.atomic.AtomicReference<*>).get() }
        .let { it?.javaClass?.simpleName == "Open" }

    private fun draining(connection: PeerConnection): Boolean = connection.javaClass
        .getDeclaredField("draining").apply { isAccessible = true }
        .get(connection)
        .let { (it as AtomicBoolean).get() }

    private fun queue(connection: PeerConnection): Collection<*> = connection.javaClass
        .getDeclaredField("sendQueue").apply { isAccessible = true }
        .get(connection) as Collection<*>
}

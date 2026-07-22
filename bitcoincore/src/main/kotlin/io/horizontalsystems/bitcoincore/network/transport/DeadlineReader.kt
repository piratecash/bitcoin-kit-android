package io.horizontalsystems.bitcoincore.network.transport

import java.io.EOFException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Monotonic clock seam so handshake deadline tests do not have to wait in real time. */
internal interface IMonotonicClock {
    fun nanoTime(): Long
}

internal object SystemMonotonicClock : IMonotonicClock {
    override fun nanoTime(): Long = System.nanoTime()
}

/**
 * Reads exactly N bytes under an absolute deadline (plan §2.2.1).
 *
 * `Socket.setSoTimeout` is an *inactivity* timeout, and any read loop re-arms it on every byte that
 * arrives. A peer dripping one byte just under the timeout would therefore hold a connection — and
 * its thread — for hours across the 4111 bytes a BIP324 handshake can carry. Bounding the whole
 * handshake instead of each individual read is what closes that.
 */
internal interface IDeadlineReader {
    fun readFully(n: Int): ByteArray
    fun readByte(): Byte
}

internal class SocketDeadlineReader(
    private val socket: Socket,
    private val input: InputStream,
    private val clock: IMonotonicClock = SystemMonotonicClock,
    timeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
) : IDeadlineReader {

    // Nanoseconds throughout: mixing a millisecond duration into a nanosecond clock yields a
    // deadline ~10^6 times too short, which would fail every handshake and silently downgrade the
    // whole network to v1.
    private val deadlineNanos = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

    override fun readFully(n: Int): ByteArray {
        val buffer = ByteArray(n)
        var offset = 0
        while (offset < n) {
            armTimeout()
            val read = input.read(buffer, offset, n - offset)
            if (read < 0) throw EOFException("Peer closed the connection after $offset of $n bytes")
            offset += read
        }
        return buffer
    }

    override fun readByte(): Byte {
        armTimeout()
        val value = input.read()
        if (value < 0) throw EOFException("Peer closed the connection")
        return value.toByte()
    }

    /** Re-arms the socket timeout to whatever is left of the absolute deadline, before every read. */
    private fun armTimeout() {
        val remainingNanos = deadlineNanos - clock.nanoTime()
        if (remainingNanos <= 0) throw HandshakeDeadlineException()
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1)
        socket.soTimeout = remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 30_000L
    }
}

/** Raised by [SocketDeadlineReader] when the absolute handshake deadline expires. */
internal class HandshakeDeadlineException : Exception("BIP324 handshake exceeded its deadline")

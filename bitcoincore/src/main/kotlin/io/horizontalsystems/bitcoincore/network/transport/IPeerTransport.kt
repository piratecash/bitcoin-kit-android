package io.horizontalsystems.bitcoincore.network.transport

import io.horizontalsystems.bitcoincore.io.BitcoinInput
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import java.io.OutputStream

/**
 * The wire envelope, abstracted so v1 and v2 can coexist (plan §2.1).
 *
 * Deliberately `internal`, along with every other type in this package: it is an implementation
 * detail of `:bitcoincore`, and exposing it would drag internal types into the public API of
 * `PeerConnection`, which does not compile in Kotlin.
 *
 * Message parsers and serializers are untouched by all of this — they operate on an already
 * unwrapped payload, so only the envelope differs between the two transports.
 */
internal interface IPeerTransport {

    /** True once an encrypted session is established; used only for logging. */
    val isEncrypted: Boolean

    /**
     * Performs any pre-application exchange. A no-op for v1; the full BIP324 handshake for v2.
     * Must complete before the `version` message is sent.
     */
    fun connect(deadlineReader: IDeadlineReader, output: OutputStream)

    /**
     * Reads exactly one message. Returns null for a packet that carries no application message —
     * a BIP324 decoy — so the caller simply keeps reading.
     */
    fun readMessage(input: BitcoinInput): IMessage?

    fun writeMessage(message: IMessage, output: OutputStream)

    /** Idempotent teardown; zeroes cipher keys and buffered keystream. */
    fun close()
}

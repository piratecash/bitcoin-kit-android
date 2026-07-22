package io.horizontalsystems.bitcoincore.network.transport

import io.horizontalsystems.bitcoincore.io.BitcoinInput
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import java.io.OutputStream

/**
 * The legacy plaintext envelope, unchanged.
 *
 * This is a pure delegation to the existing parser/serializer, including their exception types:
 * v1 keeps raising `BitcoinException` on bad magic or a failed checksum, and the receive loop keeps
 * swallowing it. That behaviour is frozen for all eight kits, so nothing here may "improve" on it —
 * only [V2Transport] raises [TransportException], and only those are fatal.
 */
internal class V1Transport(
    private val parser: NetworkMessageParser,
    private val serializer: NetworkMessageSerializer,
) : IPeerTransport {

    override val isEncrypted = false

    override fun connect(deadlineReader: IDeadlineReader, output: OutputStream) = Unit

    override fun readMessage(input: BitcoinInput): IMessage = parser.parseMessage(input)

    override fun writeMessage(message: IMessage, output: OutputStream) {
        output.write(serializer.serialize(message))
    }

    override fun close() = Unit
}

package io.horizontalsystems.bitcoincore.network.transport

import io.horizontalsystems.bitcoincore.io.BitcoinInput
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.MessageCommand
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import io.horizontalsystems.bitcoincore.network.messages.UnknownMessage
import io.horizontalsystems.bitcoincore.network.transport.v2.Bip324Cipher
import io.horizontalsystems.bitcoincore.network.transport.v2.V2Handshake
import io.horizontalsystems.bitcoincore.network.transport.v2.V2ShortIds
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.IEntropySource
import java.io.OutputStream

/**
 * BIP324 v2 encrypted transport, initiator side (plan §2.3).
 *
 * Packet: `[3B length, FSChaCha20][AEAD(header(1B) || contents, aad)][16B tag]`, where contents
 * carry either a one-byte short message id or `0x00` followed by the familiar 12-byte command.
 *
 * We always send the long form — valid per the BIP, and it removes any need for a reverse id table.
 * Decoding the short form is not optional: Bitcoin Core always emits it, and Dash-family nodes emit
 * their own 128..168 namespace unconditionally on the releases currently deployed.
 */
internal class V2Transport(
    private val magicBytes: ByteArray,
    private val usesDashShortIds: Boolean,
    private val maxContentsLength: Int,
    private val parser: NetworkMessageParser,
    private val serializer: NetworkMessageSerializer,
    entropy: IEntropySource,
) : IPeerTransport {

    private val handshake = V2Handshake(magicBytes, entropy)

    @Volatile
    private var cipher: Bip324Cipher? = null

    override val isEncrypted: Boolean
        get() = cipher != null

    override fun connect(deadlineReader: IDeadlineReader, output: OutputStream) {
        cipher = handshake.perform(deadlineReader, output).cipher
    }

    override fun readMessage(input: BitcoinInput): IMessage? {
        val session = cipher ?: throw TransportException.StreamClosed("Transport is not connected")

        val lengthField = ByteArray(Bip324Cipher.LENGTH_LEN)
        // Every read here is fatal on failure. A short read mid-packet is not recoverable: the
        // length cipher has already advanced, so resuming would interpret the remainder of this
        // packet as the next packet's length and desynchronize the stream permanently. Without this
        // translation an IOException would reach the loop's legacy catch and be swallowed.
        readFatally(input, lengthField)
        val length = session.decryptLength(lengthField)
        // Rejected before allocating: the 24-bit wire ceiling is 16 MB, and with ten peers a hostile
        // network could otherwise push an Android process out of memory before a single byte is
        // authenticated.
        if (length > maxContentsLength) {
            throw TransportException.MalformedMessage("Packet contents too large: $length")
        }

        val packet = ByteArray(Bip324Cipher.HEADER_LEN + length + TAG_LEN)
        readFatally(input, packet)
        val decrypted = session.decrypt(packet, EMPTY_AAD)
            ?: throw TransportException.AuthenticationFailed("Packet failed authentication")

        // Decoys must still be decrypted so both ciphers stay in step, but carry no message.
        if (decrypted.ignore) return null

        return decodeContents(decrypted.contents)
    }

    override fun writeMessage(message: IMessage, output: OutputStream) {
        val session = cipher ?: throw TransportException.StreamClosed("Transport is not connected")

        val (command, payload) = serializer.serializePayload(message)
        val contents = ByteArray(1 + MessageCommand.COMMAND_SIZE + payload.size)
        MessageCommand.encode(command).copyInto(contents, 1)
        payload.copyInto(contents, 1 + MessageCommand.COMMAND_SIZE)

        output.write(session.encrypt(contents, EMPTY_AAD))
    }

    override fun close() {
        cipher?.wipe()
        cipher = null
    }

    /** Reads exactly [buffer].size bytes, turning any I/O failure into a fatal transport error. */
    private fun readFatally(input: BitcoinInput, buffer: ByteArray) {
        try {
            input.readFully(buffer)
        } catch (e: java.io.IOException) {
            throw TransportException.StreamClosed("Short read on an established v2 stream: ${e.message}")
        }
    }

    private fun decodeContents(contents: ByteArray): IMessage {
        if (contents.isEmpty()) {
            throw TransportException.MalformedMessage("Empty packet contents")
        }

        val shortId = contents[0].toInt() and 0xFF
        if (shortId != 0) {
            val command = V2ShortIds.command(shortId, usesDashShortIds)
            val payload = contents.copyOfRange(1, contents.size)
            // An id we do not know is dispatched the same way v1 dispatches an unknown command,
            // rather than dropped, so the two transports behave alike above the envelope.
            return command?.let { parsePayload(it, payload) } ?: UnknownMessage("v2:$shortId")
        }

        if (contents.size < 1 + MessageCommand.COMMAND_SIZE) {
            throw TransportException.MalformedMessage("Truncated long-form message type")
        }
        val command = MessageCommand.decodeV2Strict(contents.copyOfRange(1, 1 + MessageCommand.COMMAND_SIZE))
            ?: throw TransportException.MalformedMessage("Malformed long-form message type")

        return parsePayload(command, contents.copyOfRange(1 + MessageCommand.COMMAND_SIZE, contents.size))
    }

    /**
     * A payload parser blowing up is recoverable: the packet was fully received and authenticated,
     * so the cipher state is intact and only this one message is lost. Distinguishing that from a
     * framing failure is what keeps a single malformed message from killing the connection.
     */
    private fun parsePayload(command: String, payload: ByteArray): IMessage = try {
        parser.parsePayload(command, payload)
    } catch (e: Exception) {
        throw MessagePayloadException("Failed to parse $command payload", e)
    }

    companion object {
        private const val TAG_LEN = 16
        private val EMPTY_AAD = ByteArray(0)
    }
}

package io.horizontalsystems.bitcoincore.network.messages

import io.horizontalsystems.bitcoincore.exceptions.BitcoinException
import io.horizontalsystems.bitcoincore.io.BitcoinInput
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.utils.HashUtils
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*

interface IMessage

interface IMessageParser {
    val command: String
    fun parseMessage(input: BitcoinInputMarkable): IMessage
}

interface IMessageSerializer {
    val command: String
    fun serialize(message: IMessage): ByteArray?
}

class NetworkMessageParser(private val magic: Long) {
    private var messageParsers = hashMapOf<String, IMessageParser>()

    /**
     * Parse stream as message.
     */
    @Throws(IOException::class)
    fun parseMessage(input: BitcoinInput): IMessage {
        val magic = input.readUnsignedInt()
        if (magic != this.magic) {
            throw BitcoinException("Bad magic. (local) ${this.magic}!=$magic")
        }

        val command = getCommandFrom(input.readBytes(12))
        val payloadLength = input.readInt()
        val expectedChecksum = ByteArray(4)
        input.readFully(expectedChecksum)
        // Route the payload allocation through BitcoinInput.readBytes so the
        // MAX_READ_BYTES cap applies here as well. A peer that sends an
        // oversized (or sign-flipped) payloadLength used to trigger an
        // unrecoverable OutOfMemoryError on this worker thread; now we get
        // a clean IOException and the peer-loop can disconnect.
        val payload = input.readBytes(payloadLength)

        // check:
        val actualChecksum = getCheckSum(payload)
        if (!expectedChecksum.contentEquals(actualChecksum)) {
            throw BitcoinException("Checksum failed.")
        }

        // Translation stays here rather than inside parsePayload: v1 has always surfaced a payload
        // parser failure as RuntimeException(cause), and the v2 transport needs the raw exception so
        // it can classify it as recoverable. Moving the wrapping down would change v1 behaviour.
        try {
            return parsePayload(command, payload)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    /**
     * Dispatches an already-unwrapped payload to its registered parser, propagating whatever that
     * parser throws. Shared by both transports — only the envelope differs between v1 and v2.
     */
    fun parsePayload(command: String, payload: ByteArray): IMessage {
        BitcoinInputMarkable(payload).use {
            return messageParsers[command]?.parseMessage(it) ?: UnknownMessage(command)
        }
    }

    fun add(messageParser: IMessageParser) {
        messageParsers[messageParser.command] = messageParser
    }

    private fun getCommandFrom(cmd: ByteArray): String = MessageCommand.decodeV1Legacy(cmd)

    private fun getCheckSum(payload: ByteArray): ByteArray {
        val hash = HashUtils.doubleSha256(payload)
        return hash.copyOfRange(0, 4)
    }
}

class NetworkMessageSerializer(private val magic: Long) {
    private var messageSerializers = mutableListOf<IMessageSerializer>()

    fun serialize(msg: IMessage): ByteArray {
        val (command, payload) = serializePayload(msg)

        return BitcoinOutput()
                .writeInt32(magic)                  // magic
                .write(getCommandBytes(command))    // command: char[12]
                .writeInt(payload.size)             // length: uint32_t
                .write(getCheckSum(payload))        // checksum: uint32_t
                .write(payload)                     // payload:
                .toByteArray()
    }

    /**
     * Resolves a message to its command name and serialized payload, without any envelope.
     * Shared by both transports.
     */
    fun serializePayload(msg: IMessage): Pair<String, ByteArray> {
        for (item in messageSerializers) {
            val payload = item.serialize(msg)
            if (payload != null) {
                return item.command to payload
            }
        }

        throw NoSerializer(msg)
    }

    fun add(messageSerializer: IMessageSerializer) {
        messageSerializers.add(messageSerializer)
    }

    private fun getCommandBytes(cmd: String): ByteArray = MessageCommand.encode(cmd)

    private fun getCheckSum(payload: ByteArray): ByteArray {
        val hash = HashUtils.doubleSha256(payload)
        return Arrays.copyOfRange(hash, 0, 4)
    }
}

class NoSerializer(message: IMessage) : Exception("Cannot serialize message=$message")

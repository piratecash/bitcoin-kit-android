package io.horizontalsystems.bitcoincore.network.messages

import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable

private const val TX_COMMAND = "tx"
private const val HASH_DATA_SIZE = 32
private const val MAX_STORED_EXTRA_DATA_SIZE = 64

class RejectMessage(
    val responseToMessage: String,
    val rejectCode: Byte,
    val reason: String,
    val extraData: ByteArray? = null,
    val extraDataSize: Int = extraData?.size ?: 0,
    private val rejectedHashBytes: ByteArray? = null,
) : IMessage {

    val rejectCodeName: String
        get() = when (rejectCode.toInt() and 0xff) {
            0x01 -> "malformed"
            0x10 -> "invalid"
            0x11 -> "obsolete"
            0x12 -> "duplicate"
            0x40 -> "nonstandard"
            0x41 -> "dust"
            0x42 -> "insufficientfee"
            0x43 -> "checkpoint"
            else -> "unknown"
        }

    val rejectedHash: ByteArray?
        get() = rejectedHashBytes ?: extraData?.takeIf { responseToMessage == TX_COMMAND && it.size == HASH_DATA_SIZE }

    override fun toString(): String {
        val details = mutableListOf(
            "responseToMessage=$responseToMessage",
            "rejectCode=$rejectCodeName(${rejectCode.toHexString()})",
            "reason=$reason",
        )
        val rejectedHash = rejectedHash

        if (rejectedHash != null) {
            details.add("rejectedHash=${rejectedHash.toReversedHex()}")
            if (extraDataSize > HASH_DATA_SIZE) {
                details.add("extraDataSize=$extraDataSize")
            }
        } else if (extraDataSize > 0) {
            details.add("extraDataSize=$extraDataSize")
        }

        return "RejectMessage(${details.joinToString()})"
    }

}

class RejectMessageParser : IMessageParser {
    override val command = "reject"

    override fun parseMessage(input: BitcoinInputMarkable): IMessage {
        val responseToMessage = input.readString()
        val rejectCode = input.readByte()
        val reason = input.readString()
        val extraDataSize = input.available()
        val rejectedHash = if (responseToMessage == TX_COMMAND && extraDataSize >= HASH_DATA_SIZE) {
            input.readBytes(HASH_DATA_SIZE)
        } else {
            null
        }
        val remainingExtraDataSize = extraDataSize - (rejectedHash?.size ?: 0)
        val extraData = remainingExtraDataSize
            .takeIf { it in 1..MAX_STORED_EXTRA_DATA_SIZE }
            ?.let(input::readBytes)

        return RejectMessage(
            responseToMessage = responseToMessage,
            rejectCode = rejectCode,
            reason = reason,
            extraData = extraData,
            extraDataSize = extraDataSize,
            rejectedHashBytes = rejectedHash,
        )
    }

}

private fun Byte.toHexString(): String {
    return "0x" + (toInt() and 0xff).toString(16).padStart(2, '0')
}

package io.horizontalsystems.bitcoincore.network.messages

import io.horizontalsystems.bitcoincore.exceptions.BitcoinException
import java.nio.charset.StandardCharsets

/**
 * Command-string encoding shared by both transports, with two deliberately different decoders
 * (plan §2.1).
 *
 * v1 and v2 do NOT agree on what a valid command is, and unifying them would be a regression: the
 * legacy decoder accepts things BIP324 forbids, and rejects one thing BIP324 allows. Since the
 * project commits to leaving v1 behaviour untouched for all eight kits, each transport keeps its
 * own policy.
 */
internal object MessageCommand {

    const val COMMAND_SIZE = 12

    /** 12-byte NUL-padded command, identical on both transports. */
    fun encode(command: String): ByteArray {
        val bytes = command.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= COMMAND_SIZE) { "Bad command: $command" }

        return ByteArray(COMMAND_SIZE).also { bytes.copyInto(it) }
    }

    /**
     * Legacy v1 decoder — a verbatim move of the original `getCommandFrom`, quirks included:
     * only trailing NULs are stripped (so an embedded NUL survives into the string), non-printable
     * bytes are accepted, and the `n <= 0` guard rejects an otherwise legitimate one-character
     * command. Frozen by V1FrameGoldenTest; do not "fix" any of it here.
     */
    fun decodeV1Legacy(command: ByteArray): String {
        var n = command.size - 1
        while (n >= 0) {
            if (command[n].toInt() == 0) {
                n--
            } else {
                break
            }
        }
        if (n <= 0) {
            throw BitcoinException("Bad command bytes.")
        }

        return String(command.copyOfRange(0, n + 1), StandardCharsets.UTF_8)
    }

    /**
     * BIP324 decoder. Returns null when the field is malformed: empty, containing a byte outside
     * printable ASCII before the first NUL, or carrying a non-zero byte after it.
     */
    fun decodeV2Strict(command: ByteArray): String? {
        if (command.size != COMMAND_SIZE) return null

        var length = 0
        while (length < COMMAND_SIZE && command[length].toInt() != 0) {
            val byte = command[length].toInt() and 0xFF
            if (byte < 0x20 || byte > 0x7E) return null
            length++
        }
        if (length == 0) return null
        for (index in length until COMMAND_SIZE) {
            if (command[index].toInt() != 0) return null
        }

        return String(command.copyOfRange(0, length), StandardCharsets.US_ASCII)
    }
}

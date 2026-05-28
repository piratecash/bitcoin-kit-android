package io.horizontalsystems.bitcoincore.network.messages

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RejectMessageParserTest {

    private val parser = RejectMessageParser()

    @Test
    fun parseMessage_txRejectWithHash_extractsRejectedHash() {
        val hash = ByteArray(32) { it.toByte() }
        val rejectMessage = parser.parseMessage(
            BitcoinInputMarkable(
                rejectPayload(
                    responseToMessage = "tx",
                    rejectCode = 0x10,
                    reason = "invalid",
                    extraData = hash,
                )
            )
        ) as RejectMessage

        assertEquals("tx", rejectMessage.responseToMessage)
        assertEquals("invalid", rejectMessage.reason)
        assertEquals(32, rejectMessage.extraDataSize)
        assertNull(rejectMessage.extraData)
        assertArrayEquals(hash, rejectMessage.rejectedHash)
    }

    @Test
    fun parseMessage_nonTxRejectWithHash_doesNotExposeRejectedHash() {
        val hash = ByteArray(32) { (31 - it).toByte() }
        val rejectMessage = parser.parseMessage(
            BitcoinInputMarkable(
                rejectPayload(
                    responseToMessage = "block",
                    rejectCode = 0x12,
                    reason = "duplicate",
                    extraData = hash,
                )
            )
        ) as RejectMessage

        assertEquals("block", rejectMessage.responseToMessage)
        assertEquals(32, rejectMessage.extraDataSize)
        assertArrayEquals(hash, rejectMessage.extraData)
        assertNull(rejectMessage.rejectedHash)
    }

    @Test
    fun parseMessage_largeTxExtraData_extractsRejectedHashWithoutStoringTail() {
        val rejectedHash = ByteArray(32) { it.toByte() }
        val extraData = ByteArray(128) { (it + 32).toByte() }
        val rejectMessage = parser.parseMessage(
            BitcoinInputMarkable(
                rejectPayload(
                    responseToMessage = "tx",
                    rejectCode = 0x40,
                    reason = "nonstandard",
                    extraData = rejectedHash + extraData,
                )
            )
        ) as RejectMessage

        assertEquals("tx", rejectMessage.responseToMessage)
        assertEquals(160, rejectMessage.extraDataSize)
        assertNull(rejectMessage.extraData)
        assertArrayEquals(rejectedHash, rejectMessage.rejectedHash)
    }

    private fun rejectPayload(
        responseToMessage: String,
        rejectCode: Int,
        reason: String,
        extraData: ByteArray = byteArrayOf(),
    ): ByteArray {
        return BitcoinOutput()
            .writeString(responseToMessage)
            .writeByte(rejectCode)
            .writeString(reason)
            .write(extraData)
            .toByteArray()
    }
}

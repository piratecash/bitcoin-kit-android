package io.horizontalsystems.cosantakit.messages

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Cosanta daemon at PROTOCOL_VERSION (70228) serialises CSimplifiedMNListDiff
 * with nVersion (uint16) AFTER cbTx — same layout as DashKit's parser.
 * The previous Cosanta parser skipped this field
 * (`val version = 0// (remove) input.readUnsignedShort()`), shifting every
 * subsequent read by 2 bytes; a varint inside the deletedMNs list / quorum
 * list eventually came out enormous and crashed the worker thread.
 */
class MasternodeListDiffMessageParserTest {

    @Test
    fun parsesMessage_consumesNVersionAfterCoinbaseTx() {
        // We pick nVersion's first byte = 0x05 specifically to detect the
        // off-by-2 bug. If the parser correctly consumes the 2-byte nVersion
        // after cbTx, the next varInt (deletedMNsCount) reads as 0 (the
        // trailing 0x00 byte we write). If the parser skips nVersion, that
        // 0x05 gets interpreted as deletedMNsCount = 5, and the parser then
        // tries to read 5 ByteArray(32) hashes from an empty tail and
        // either throws EOF or returns a deletedMNs list of size > 0.
        val baseBlockHash = ByteArray(32) { 0xAA.toByte() }
        val blockHash = ByteArray(32) { 0xBB.toByte() }
        val payload = buildMinimalCosantaMnlistdiff(
            nVersionFirstByte = 0x05,
            baseBlockHash = baseBlockHash,
            blockHash = blockHash,
        )

        val message = MasternodeListDiffMessageParser()
            .parseMessage(BitcoinInputMarkable(payload)) as MasternodeListDiffMessage

        assertArrayEquals(baseBlockHash, message.baseBlockHash)
        assertArrayEquals(blockHash, message.blockHash)
        assertEquals(
            "deletedMNs must be empty. If the parser skipped nVersion, the " +
                "first nVersion byte (0x05) would be read as deletedMNsCount = 5 " +
                "and we'd see 5 entries (or an EOF exception).",
            0, message.deletedMNs.size
        )
        assertEquals(0, message.mnList.size)
        assertEquals(0, message.deletedQuorums.size)
        assertEquals(0, message.quorumList.size)
    }

    @Test
    fun parsesMessage_doesNotThrowOnMinimalValidPayload() {
        val payload = buildMinimalCosantaMnlistdiff()

        var threw: Throwable? = null
        try {
            MasternodeListDiffMessageParser().parseMessage(BitcoinInputMarkable(payload))
        } catch (e: Throwable) {
            threw = e
        }
        assertTrue(
            "Parser must accept a valid minimal mnlistdiff (got: ${threw?.javaClass?.simpleName} ${threw?.message})",
            threw == null
        )
    }

    private fun buildMinimalCosantaMnlistdiff(
        nVersionFirstByte: Int = 0x00,
        baseBlockHash: ByteArray = ByteArray(32) { 0xAA.toByte() },
        blockHash: ByteArray = ByteArray(32) { 0xBB.toByte() },
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(baseBlockHash)
        out.write(blockHash)
        // CPartialMerkleTree
        out.write(intArrayOf(1, 0, 0, 0).map { it.toByte() }.toByteArray()) // totalTransactions = 1
        out.write(0) // merkleHashesCount = 0
        out.write(0) // merkleFlagsCount = 0
        // Coinbase tx (DIP2)
        out.write(intArrayOf(1, 0, 0, 0).map { it.toByte() }.toByteArray()) // version=1, type=0
        out.write(1)                                                         // inputCount = 1
        out.write(ByteArray(32))
        out.write(intArrayOf(0xFF, 0xFF, 0xFF, 0xFF).map { it.toByte() }.toByteArray())
        out.write(0)
        out.write(intArrayOf(0xFF, 0xFF, 0xFF, 0xFF).map { it.toByte() }.toByteArray())
        out.write(0)                                                         // outputCount = 0
        out.write(ByteArray(4))                                              // lockTime = 0
        // nVersion (uint16 LE) — AFTER cbTx for Cosanta
        out.write(nVersionFirstByte and 0xFF)
        out.write(0)
        // Empty lists
        out.write(0) // deletedMNsCount
        out.write(0) // mnListCount
        out.write(0) // deletedQuorumsCount
        out.write(0) // newQuorumsCount
        return out.toByteArray()
    }
}

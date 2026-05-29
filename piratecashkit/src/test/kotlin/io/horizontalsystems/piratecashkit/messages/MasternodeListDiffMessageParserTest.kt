package io.horizontalsystems.piratecashkit.messages

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * PirateCash daemon at PROTOCOL_VERSION >= MNLISTDIFF_VERSION_ORDER (70229)
 * places the 2-byte `nVersion` field **before** `baseBlockHash` in
 * CSimplifiedMNListDiff (see PirateCash:src/evo/simplifiedmns.h
 * `SERIALIZE_METHODS`). Our app announces protocolVersion = 70229
 * (`MainNetPirateCash.kt`), so every mnlistdiff we receive uses this layout.
 *
 * The previous parser skipped `nVersion` entirely. Every byte was therefore
 * read 2 positions too early, eventually causing a varint inside the
 * coinbase tx's sigScript length to come out as ~432 MB and crash the worker
 * thread with OOM in BitcoinInput.readBytes.
 */
class MasternodeListDiffMessageParserTest {

    @Test
    fun parsesMessageHeader_withNVersionBeforeBaseBlockHash() {
        val baseBlockHash = ByteArray(32) { 0xAA.toByte() }
        val blockHash = ByteArray(32) { 0xBB.toByte() }
        val payload = buildMinimalPirateCashMnlistdiff(
            nVersion = 1,
            baseBlockHash = baseBlockHash,
            blockHash = blockHash,
        )

        val message = MasternodeListDiffMessageParser()
            .parseMessage(BitcoinInputMarkable(payload)) as MasternodeListDiffMessage

        assertArrayEquals(
            "baseBlockHash must equal the bytes WRITTEN as baseBlockHash. " +
                "If the parser forgot to read the 2-byte nVersion prefix, it shifts " +
                "the entire stream by 2 bytes and baseBlockHash starts with the " +
                "leftover nVersion bytes.",
            baseBlockHash, message.baseBlockHash
        )
        assertArrayEquals(blockHash, message.blockHash)
        assertEquals(0, message.deletedMNs.size)
        assertEquals(0, message.mnList.size)
        assertEquals(0, message.deletedQuorums.size)
        assertEquals(0, message.quorumList.size)
    }

    @Test
    fun parsesMessage_doesNotThrowOnMinimalValidPayload() {
        // Without the fix the parser drifts 2 bytes per message and eventually
        // tries to read a multi-hundred-MB byte array from a sigScript length.
        // BitcoinInputReadBytesLimitTest already prevents the OOM, but the
        // parser itself must not throw on a valid payload.
        val payload = buildMinimalPirateCashMnlistdiff()

        val parser = MasternodeListDiffMessageParser()
        var threw: Throwable? = null
        try {
            parser.parseMessage(BitcoinInputMarkable(payload))
        } catch (e: Throwable) {
            threw = e
        }
        assertTrue(
            "Parser must accept a valid minimal mnlistdiff (got: ${threw?.javaClass?.simpleName} ${threw?.message})",
            threw == null
        )
    }

    private fun buildMinimalPirateCashMnlistdiff(
        nVersion: Int = 1,
        baseBlockHash: ByteArray = ByteArray(32) { 0xAA.toByte() },
        blockHash: ByteArray = ByteArray(32) { 0xBB.toByte() },
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // nVersion (uint16 LE) — new in MNLISTDIFF_VERSION_ORDER (70229)
        out.write(nVersion and 0xFF)
        out.write((nVersion shr 8) and 0xFF)
        out.write(baseBlockHash)
        out.write(blockHash)
        // CPartialMerkleTree: totalTransactions(u32) + hashes[] + flags[]
        out.write(intArrayOf(1, 0, 0, 0).map { it.toByte() }.toByteArray()) // totalTransactions = 1
        out.write(0) // merkleHashesCount = 0 (varInt)
        out.write(0) // merkleFlagsCount = 0 (varInt)
        // Coinbase tx (DIP2: int32 version | type)
        out.write(intArrayOf(1, 0, 0, 0).map { it.toByte() }.toByteArray()) // version=1, type=0
        out.write(1)                                                         // inputCount = 1
        out.write(ByteArray(32))                                             // prev hash = 0×32
        out.write(intArrayOf(0xFF, 0xFF, 0xFF, 0xFF).map { it.toByte() }.toByteArray()) // prev idx
        out.write(0)                                                         // sigScriptLen = 0
        out.write(intArrayOf(0xFF, 0xFF, 0xFF, 0xFF).map { it.toByte() }.toByteArray()) // sequence
        out.write(0)                                                         // outputCount = 0
        out.write(ByteArray(4))                                              // lockTime = 0
        // Empty lists
        out.write(0) // deletedMNsCount
        out.write(0) // mnListCount
        out.write(0) // deletedQuorumsCount
        out.write(0) // newQuorumsCount
        return out.toByteArray()
    }
}

package io.horizontalsystems.piratecashkit.messages

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.utils.HashUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun parsesMessage_withVersionedRegularMasternodeEntry() {
        val masternodeEntry = buildVersionedMasternodeEntry(type = REGULAR_MASTERNODE_TYPE)
        val payload = buildMinimalPirateCashMnlistdiff(masternodeEntries = listOf(masternodeEntry.bytes))

        val message = MasternodeListDiffMessageParser()
            .parseMessage(BitcoinInputMarkable(payload)) as MasternodeListDiffMessage
        val masternode = message.mnList.single()

        assertEquals(BASIC_BLS_VERSION, masternode.nVersion)
        assertArrayEquals(masternodeEntry.proRegTxHash, masternode.proRegTxHash)
        assertEquals(REGULAR_MASTERNODE_TYPE, masternode.type)
        assertNull(masternode.platformHTTPPort)
        assertNull(masternode.platformNodeID)
        assertArrayEquals(masternodeEntry.expectedHash, masternode.hash)
    }

    @Test
    fun parsesMessage_withVersionedEvoMasternodeEntry() {
        val platformNodeID = ByteArray(20) { 0x7A.toByte() }
        val masternodeEntry = buildVersionedMasternodeEntry(
            type = EVO_MASTERNODE_TYPE,
            platformHTTPPort = 443,
            platformNodeID = platformNodeID,
        )
        val payload = buildMinimalPirateCashMnlistdiff(masternodeEntries = listOf(masternodeEntry.bytes))

        val message = MasternodeListDiffMessageParser()
            .parseMessage(BitcoinInputMarkable(payload)) as MasternodeListDiffMessage
        val masternode = message.mnList.single()

        assertEquals(BASIC_BLS_VERSION, masternode.nVersion)
        assertEquals(EVO_MASTERNODE_TYPE, masternode.type)
        assertEquals(443, masternode.platformHTTPPort)
        assertArrayEquals(platformNodeID, masternode.platformNodeID)
        assertArrayEquals(masternodeEntry.expectedHash, masternode.hash)
    }

    @Test
    fun parsesMessage_withVersionedMasternodeBeforeQuorum_doesNotDrift() {
        val masternodeEntry = buildVersionedMasternodeEntry(type = REGULAR_MASTERNODE_TYPE)
        val quorum = buildMinimalQuorum()
        val payload = buildMinimalPirateCashMnlistdiff(
            masternodeEntries = listOf(masternodeEntry.bytes),
            quorums = listOf(quorum),
        )

        val message = MasternodeListDiffMessageParser()
            .parseMessage(BitcoinInputMarkable(payload)) as MasternodeListDiffMessage

        assertEquals(1, message.mnList.size)
        assertEquals(1, message.quorumList.size)
    }

    private fun buildMinimalPirateCashMnlistdiff(
        nVersion: Int = 1,
        baseBlockHash: ByteArray = ByteArray(32) { 0xAA.toByte() },
        blockHash: ByteArray = ByteArray(32) { 0xBB.toByte() },
        masternodeEntries: List<ByteArray> = emptyList(),
        quorums: List<ByteArray> = emptyList(),
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
        out.write(masternodeEntries.size) // mnListCount
        masternodeEntries.forEach(out::write)
        out.write(0) // deletedQuorumsCount
        out.write(quorums.size) // newQuorumsCount
        quorums.forEach(out::write)
        return out.toByteArray()
    }

    private fun buildVersionedMasternodeEntry(
        type: Int,
        platformHTTPPort: Int? = null,
        platformNodeID: ByteArray? = null,
    ): MasternodeEntryFixture {
        val proRegTxHash = ByteArray(32) { 0x11 }
        val confirmedHash = ByteArray(32) { 0x22 }
        val ipAddress = ByteArray(16) { 0x33 }
        val port = 63636
        val pubKeyOperator = ByteArray(48) { 0x44 }
        val keyIDVoting = ByteArray(20) { 0x55 }
        val isValid = true

        val out = ByteArrayOutputStream()
        out.writeUnsignedShort(BASIC_BLS_VERSION)
        out.write(proRegTxHash)
        out.write(confirmedHash)
        out.write(ipAddress)
        out.writeUnsignedShort(port)
        out.write(pubKeyOperator)
        out.write(keyIDVoting)
        out.write(if (isValid) 1 else 0)
        out.writeUnsignedShort(type)
        if (type == EVO_MASTERNODE_TYPE) {
            out.writeUnsignedShort(checkNotNull(platformHTTPPort))
            out.write(checkNotNull(platformNodeID))
        }

        val hashPayload = BitcoinOutput()
            .write(proRegTxHash)
            .write(confirmedHash)
            .write(ipAddress)
            .writeUnsignedShort(port)
            .write(pubKeyOperator)
            .write(keyIDVoting)
            .writeByte(if (isValid) 1 else 0)
            .writeUnsignedShort(type)

        if (type == EVO_MASTERNODE_TYPE) {
            hashPayload
                .writeUnsignedShort(checkNotNull(platformHTTPPort))
                .write(checkNotNull(platformNodeID))
        }

        return MasternodeEntryFixture(
            bytes = out.toByteArray(),
            proRegTxHash = proRegTxHash,
            expectedHash = HashUtils.doubleSha256(hashPayload.toByteArray()),
        )
    }

    private fun buildMinimalQuorum(): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeUnsignedShort(1) // nVersion = LEGACY_BLS_NON_INDEXED_QUORUM_VERSION
        out.write(1) // llmqType
        out.write(ByteArray(32) { 0x66 })
        out.write(0) // signers size
        out.write(0) // validMembers size
        out.write(ByteArray(48) { 0x77 })
        out.write(ByteArray(32) { 0x08 })
        out.write(ByteArray(96) { 0x09 })
        out.write(ByteArray(96) { 0x0A })
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeUnsignedShort(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private data class MasternodeEntryFixture(
        val bytes: ByteArray,
        val proRegTxHash: ByteArray,
        val expectedHash: ByteArray,
    )

    private companion object {
        const val BASIC_BLS_VERSION = 2
        const val REGULAR_MASTERNODE_TYPE = 0
        const val EVO_MASTERNODE_TYPE = 1
    }
}

package io.horizontalsystems.litecoinkit.mweb.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MwebDatabaseJvmTest {

    private lateinit var dataDir: File
    private lateinit var database: MwebDatabase

    @Before
    fun setUp() {
        dataDir = Files.createTempDirectory("mweb-jvm").toFile()
        database = MwebDatabase.getInstance(dataDir.path, "mweb.db")
    }

    @After
    fun tearDown() {
        database.close()
        dataDir.deleteRecursively()
    }

    @Test
    fun save_outgoingTransactionWithOutputIds_roundTripsListsOnJvm() {
        database.outgoingTransactionDao.save(outgoing("tx-1", listOf("out-a", "out-b"), listOf("spent-a")))

        val stored = database.outgoingTransactionDao.outgoingTransactions().single()

        assertEquals(listOf("out-a", "out-b"), stored.createdOutputIds)
        assertEquals(listOf("spent-a"), stored.spentOutputIds)
    }

    /** The DAO filters on the literal `'[]'`, so an empty list must serialize to exactly that. */
    @Test
    fun confirmedOutgoingTransactionsWithCreatedOutputs_emptyCreatedOutputIds_excludesTransaction() {
        database.outgoingTransactionDao.save(outgoing("tx-empty", emptyList(), emptyList()))
        database.outgoingTransactionDao.save(outgoing("tx-filled", listOf("out-a"), emptyList()))

        val uids = database.outgoingTransactionDao.confirmedOutgoingTransactionsWithCreatedOutputs().map { it.uid }

        assertEquals(listOf("tx-filled"), uids)
    }

    /** Rows written before the converter moved off org.json must still decode. */
    @Test
    fun toStringList_legacyJsonArrayString_returnsEveryElement() {
        assertEquals(listOf("out-a", "out-b"), MwebTypeConverters().toStringList("""["out-a","out-b"]"""))
    }

    private fun outgoing(uid: String, createdOutputIds: List<String>, spentOutputIds: List<String>) =
        MwebOutgoingTransactionEntity(
            uid = uid,
            type = "Outgoing",
            kind = "Mweb",
            amount = 1_000L,
            fee = 10L,
            destinationAddress = "ltcmweb1qq",
            canonicalTransactionHash = null,
            createdOutputIds = createdOutputIds,
            spentOutputIds = spentOutputIds,
            confirmedHeight = 100,
            confirmedTimestamp = 1_700_000_000L,
            timestamp = 1_700_000_000L,
        )
}

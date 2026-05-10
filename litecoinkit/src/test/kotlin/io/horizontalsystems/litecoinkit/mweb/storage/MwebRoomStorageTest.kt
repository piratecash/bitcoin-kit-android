package io.horizontalsystems.litecoinkit.mweb.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.litecoinkit.mweb.MwebTransactionKind
import io.horizontalsystems.litecoinkit.mweb.MwebTransactionType
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MwebRoomStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, MwebDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val storage = MwebRoomStorage(database)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun outgoingTransactions_nullableFields_preservesNulls() {
        database.outgoingTransactionDao.save(
            entity(
                uid = "tx",
                type = MwebTransactionType.Incoming.name,
                kind = MwebTransactionKind.PublicToMweb.name,
                fee = null,
                destinationAddress = "",
            )
        )

        val transaction = storage.localTransactions().single()

        assertEquals(MwebTransactionType.Incoming, transaction.type)
        assertEquals(MwebTransactionKind.PublicToMweb, transaction.kind)
        assertNull(transaction.fee)
        assertNull(transaction.address)
    }

    @Test
    fun outgoingTransactions_unknownEnumValues_skipsInvalidRows() {
        database.outgoingTransactionDao.save(
            entity(
                uid = "valid",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToMweb.name,
            )
        )
        database.outgoingTransactionDao.save(
            entity(
                uid = "invalid-kind",
                type = MwebTransactionType.Outgoing.name,
                kind = "FutureKind",
            )
        )
        database.outgoingTransactionDao.save(
            entity(
                uid = "invalid-type",
                type = "FutureType",
                kind = MwebTransactionKind.MwebToMweb.name,
            )
        )

        assertEquals(listOf("valid"), storage.localTransactions().map { it.uid })
    }

    @Test
    fun localTransactions_confirmedFields_restoresCompletedTransaction() {
        database.outgoingTransactionDao.save(
            entity(
                uid = "confirmed",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                confirmedHeight = 123,
                confirmedTimestamp = 2_000,
            )
        )

        val transaction = storage.localTransactions().single()

        assertEquals(123, transaction.height)
        assertEquals(2_000L, transaction.timestamp)
        assertFalse(transaction.pending)
    }

    @Test
    fun localTransactions_mwebToPublicLegacyBroadcastHash_hidesCanonicalHash() {
        database.outgoingTransactionDao.save(
            entity(
                uid = "mweb-outgoing:local-broadcast-hash",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                canonicalTransactionHash = "local-broadcast-hash",
                confirmedHeight = 123,
            )
        )

        val transaction = storage.localTransactions().single()

        assertNull(transaction.canonicalTransactionHash)
    }

    @Test
    fun localTransactions_publicToMwebCanonicalHash_keepsCanonicalHash() {
        database.outgoingTransactionDao.save(
            entity(
                uid = "mweb-incoming:public-hash",
                type = MwebTransactionType.Incoming.name,
                kind = MwebTransactionKind.PublicToMweb.name,
                canonicalTransactionHash = "public-hash",
                confirmedHeight = 123,
            )
        )

        val transaction = storage.localTransactions().single()

        assertEquals("public-hash", transaction.canonicalTransactionHash)
    }

    @Test
    fun mwebToPublicCanonicalHashHeights_returnsOnlyMissingOrLegacyHashes() {
        database.outgoingTransactionDao.save(
            entity(
                uid = "mweb-outgoing:legacy-hash",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                canonicalTransactionHash = "legacy-hash",
                confirmedHeight = 200,
            )
        )
        database.outgoingTransactionDao.save(
            entity(
                uid = "missing-hash",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                canonicalTransactionHash = null,
                confirmedHeight = 201,
            )
        )
        database.outgoingTransactionDao.save(
            entity(
                uid = "already-fixed",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                canonicalTransactionHash = "canonical-hash",
                confirmedHeight = 202,
            )
        )
        database.outgoingTransactionDao.save(
            entity(
                uid = "public-peg-in",
                type = MwebTransactionType.Incoming.name,
                kind = MwebTransactionKind.PublicToMweb.name,
                canonicalTransactionHash = null,
                confirmedHeight = 203,
            )
        )

        val heights = storage.mwebToPublicCanonicalHashHeights()

        assertEquals(listOf(201, 200), heights)
    }

    @Test
    fun updateMwebToPublicCanonicalHashes_updatesConfirmedPegOutsAtHeight() {
        database.outgoingTransactionDao.save(
            entity(
                uid = "mweb-outgoing:legacy-hash",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                canonicalTransactionHash = "legacy-hash",
                confirmedHeight = 200,
            )
        )

        assertTrue(storage.updateMwebToPublicCanonicalHashes(listOf(200 to "canonical-hash")))

        val transaction = storage.localTransactions().single()
        assertEquals("canonical-hash", transaction.canonicalTransactionHash)
        assertTrue(storage.mwebToPublicCanonicalHashHeights().isEmpty())
    }

    @Test
    fun updateMwebToPublicCanonicalHashes_multiplePegOutsSameHeight_assignsSameHash() {
        database.outgoingTransactionDao.save(
            entity(
                uid = "mweb-outgoing:legacy-a",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                canonicalTransactionHash = "legacy-a",
                confirmedHeight = 200,
            )
        )
        database.outgoingTransactionDao.save(
            entity(
                uid = "mweb-outgoing:legacy-b",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                canonicalTransactionHash = "legacy-b",
                confirmedHeight = 200,
            )
        )

        assertTrue(storage.updateMwebToPublicCanonicalHashes(listOf(200 to "canonical-hash")))

        val transactions = storage.localTransactions()
        assertEquals(listOf("canonical-hash", "canonical-hash"), transactions.map { it.canonicalTransactionHash })
    }

    @Test
    fun localTransactions_mwebToMwebLegacyBroadcastHash_hidesCanonicalHash() {
        database.outgoingTransactionDao.save(
            entity(
                uid = "mweb-outgoing:local-mweb-hash",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToMweb.name,
                canonicalTransactionHash = "local-mweb-hash",
                confirmedHeight = 123,
            )
        )

        val transaction = storage.localTransactions().single()

        assertNull(transaction.canonicalTransactionHash)
    }

    @Test
    fun confirmTransactionsSpending_requiresAllSpentOutputs() {
        database.outgoingTransactionDao.save(
            entity(
                uid = "single-input",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                spentOutputIds = listOf("input-a"),
            )
        )
        database.outgoingTransactionDao.save(
            entity(
                uid = "multi-input",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                spentOutputIds = listOf("input-a", "input-b"),
            )
        )

        storage.confirmTransactionsSpending(listOf("input-a"), height = 200, timestamp = 3_000)

        val partiallyConfirmed = storage.localTransactions().associateBy { it.uid }
        assertEquals(200, partiallyConfirmed.getValue("single-input").height)
        assertNull(partiallyConfirmed.getValue("multi-input").height)

        storage.confirmTransactionsSpending(listOf("input-a", "input-b"), height = 201, timestamp = null)

        val confirmed = storage.localTransactions().associateBy { it.uid }
        assertEquals(200, confirmed.getValue("single-input").height)
        assertEquals(3_000L, confirmed.getValue("single-input").timestamp)
        assertEquals(201, confirmed.getValue("multi-input").height)
        assertEquals(1_000L, confirmed.getValue("multi-input").timestamp)
    }

    @Test
    fun confirmTransactionsSpending_emptyLocalSpentOutputs_keepsTransactionPending() {
        database.outgoingTransactionDao.save(
            entity(
                uid = "peg-in",
                type = MwebTransactionType.Incoming.name,
                kind = MwebTransactionKind.PublicToMweb.name,
                spentOutputIds = emptyList(),
            )
        )

        storage.confirmTransactionsSpending(listOf("input-a"), height = 200, timestamp = 3_000)

        val transaction = storage.localTransactions().single()
        assertNull(transaction.height)
        assertTrue(transaction.pending)
    }

    @Test
    fun confirmTransactionsSpending_nonPositiveHeight_keepsTransactionPending() {
        database.outgoingTransactionDao.save(
            entity(
                uid = "pending",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                spentOutputIds = listOf("input-a"),
            )
        )

        storage.confirmTransactionsSpending(listOf("input-a"), height = 0, timestamp = 3_000)

        val transaction = storage.localTransactions().single()
        assertNull(transaction.height)
        assertTrue(transaction.pending)
    }

    @Test
    fun confirmTransactionsSpending_createdUtxo_confirmsCreatedUtxo() {
        storage.saveUtxos(listOf(utxo(outputId = "change-output", height = 0, blockTime = 0)))
        database.outgoingTransactionDao.save(
            entity(
                uid = "peg-out",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                spentOutputIds = listOf("input-a"),
                createdOutputIds = listOf("change-output"),
            )
        )

        storage.confirmTransactionsSpending(listOf("input-a"), height = 200, timestamp = 3_000)

        val transaction = storage.localTransactions().single()
        val utxo = storage.utxos().single()
        assertEquals(200, transaction.height)
        assertEquals(200, utxo.height)
        assertEquals(3_000L, utxo.blockTime)
        assertFalse(utxo.spent)
    }

    @Test
    fun reconcileCreatedUtxos_existingConfirmedTransaction_confirmsCreatedUtxo() {
        storage.saveUtxos(listOf(utxo(outputId = "change-output", height = 0, blockTime = 0)))
        database.outgoingTransactionDao.save(
            entity(
                uid = "confirmed-peg-out",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                createdOutputIds = listOf("change-output"),
                confirmedHeight = 200,
                confirmedTimestamp = null,
            )
        )

        storage.reconcileCreatedUtxos()

        val utxo = storage.utxos().single()
        assertEquals(200, utxo.height)
        assertEquals(1_000L, utxo.blockTime)
        assertFalse(utxo.spent)
    }

    @Test
    fun reconcileCreatedUtxos_lateArrivingCreatedUtxo_confirmsCreatedUtxo() {
        database.outgoingTransactionDao.save(
            entity(
                uid = "confirmed-peg-out",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                createdOutputIds = listOf("change-output"),
                confirmedHeight = 200,
                confirmedTimestamp = 3_000,
            )
        )

        storage.reconcileCreatedUtxos()
        assertTrue(storage.utxos().isEmpty())

        storage.saveUtxos(listOf(utxo(outputId = "change-output", height = 0, blockTime = 0)))
        storage.reconcileCreatedUtxos()

        val utxo = storage.utxos().single()
        assertEquals(200, utxo.height)
        assertEquals(3_000L, utxo.blockTime)
        assertFalse(utxo.spent)
    }

    @Test
    fun reconcileCreatedUtxos_confirmedCreatedUtxo_keepsOriginalHeight() {
        storage.saveUtxos(
            listOf(
                utxo(outputId = "confirmed-change", height = 100, blockTime = 2_000),
                utxo(outputId = "unconfirmed-change", height = 0, blockTime = 0),
            )
        )
        database.outgoingTransactionDao.save(
            entity(
                uid = "confirmed-peg-out",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                createdOutputIds = listOf("confirmed-change", "unconfirmed-change"),
                confirmedHeight = 200,
                confirmedTimestamp = 3_000,
            )
        )

        storage.reconcileCreatedUtxos()

        val utxos = storage.utxos().associateBy { it.outputId }
        utxos.getValue("confirmed-change").let { utxo ->
            assertEquals(100, utxo.height)
            assertEquals(2_000L, utxo.blockTime)
        }
        utxos.getValue("unconfirmed-change").let { utxo ->
            assertEquals(200, utxo.height)
            assertEquals(3_000L, utxo.blockTime)
        }
    }

    @Test
    fun reconcileCreatedUtxos_nonPositiveConfirmedHeight_keepsCreatedUtxoUnconfirmed() {
        storage.saveUtxos(listOf(utxo(outputId = "change-output", height = 0, blockTime = 123)))
        database.outgoingTransactionDao.save(
            entity(
                uid = "confirmed-peg-out",
                type = MwebTransactionType.Outgoing.name,
                kind = MwebTransactionKind.MwebToPublic.name,
                createdOutputIds = listOf("change-output"),
                confirmedHeight = 0,
                confirmedTimestamp = 3_000,
            )
        )

        storage.reconcileCreatedUtxos()

        val utxo = storage.utxos().single()
        assertEquals(0, utxo.height)
        assertEquals(123L, utxo.blockTime)
    }

    @Test
    fun markSpent_unconfirmedUtxo_keepsUnspent() {
        storage.saveUtxos(listOf(utxo(outputId = "unconfirmed", height = 0)))

        storage.markSpent(listOf("unconfirmed"))

        assertFalse(storage.utxos().single().spent)
    }

    @Test
    fun markSpent_confirmedUtxo_marksSpent() {
        storage.saveUtxos(listOf(utxo(outputId = "confirmed", height = 100)))

        storage.markSpent(listOf("confirmed"))

        assertTrue(storage.utxos().single().spent)
    }

    private fun utxo(outputId: String, height: Int, blockTime: Long = 1_000) = MwebUtxo(
        outputId = outputId,
        address = "address",
        addressIndex = 1,
        value = 100,
        height = height,
        blockTime = blockTime,
        spent = false,
    )

    private fun entity(
        uid: String,
        type: String,
        kind: String,
        fee: Long? = 1,
        destinationAddress: String? = "destination",
        canonicalTransactionHash: String? = "hash-$uid",
        spentOutputIds: List<String> = emptyList(),
        createdOutputIds: List<String> = listOf("output-$uid"),
        confirmedHeight: Int? = null,
        confirmedTimestamp: Long? = null,
    ) = MwebOutgoingTransactionEntity(
        uid = uid,
        type = type,
        kind = kind,
        amount = 100,
        fee = fee,
        destinationAddress = destinationAddress,
        canonicalTransactionHash = canonicalTransactionHash,
        createdOutputIds = createdOutputIds,
        spentOutputIds = spentOutputIds,
        confirmedHeight = confirmedHeight,
        confirmedTimestamp = confirmedTimestamp,
        timestamp = 1_000,
    )
}

package io.horizontalsystems.litecoinkit.mweb.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.litecoinkit.mweb.MwebTransactionKind
import io.horizontalsystems.litecoinkit.mweb.MwebTransactionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun entity(
        uid: String,
        type: String,
        kind: String,
        fee: Long? = 1,
        destinationAddress: String? = "destination",
    ) = MwebOutgoingTransactionEntity(
        uid = uid,
        type = type,
        kind = kind,
        amount = 100,
        fee = fee,
        destinationAddress = destinationAddress,
        canonicalTransactionHash = "hash-$uid",
        createdOutputIds = listOf("output-$uid"),
        spentOutputIds = emptyList(),
        timestamp = 1_000,
    )
}

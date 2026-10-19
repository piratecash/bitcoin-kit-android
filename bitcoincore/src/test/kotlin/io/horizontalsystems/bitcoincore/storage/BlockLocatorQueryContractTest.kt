package io.horizontalsystems.bitcoincore.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.models.Block
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class BlockLocatorQueryContractTest {

    private lateinit var database: CoreDatabase
    private lateinit var storage: Storage

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CoreDatabase::class.java
        )
            .setTransactionExecutor(Executors.newSingleThreadExecutor())
            .setQueryExecutor(Executors.newSingleThreadExecutor())
            .allowMainThreadQueries()
            .build()

        storage = Storage(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `getBlocks with sortedBy - sorting argument is ignored and the highest block comes first`() {
        insert(105)
        insert(110)

        val blocks = storage.getBlocks(heightGreaterThan = 100, sortedBy = "height", limit = 1)

        assertEquals(listOf(110), blocks.map { it.height })
    }

    private fun insert(height: Int) {
        database.block.insert(Block().apply {
            this.height = height
            this.headerHash = byteArrayOf(height.toByte())
            this.previousBlockHash = byteArrayOf((height - 1).toByte())
        })
    }
}

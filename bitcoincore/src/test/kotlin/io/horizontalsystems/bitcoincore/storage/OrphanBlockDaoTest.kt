package io.horizontalsystems.bitcoincore.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.models.OrphanBlock
import io.horizontalsystems.bitcoincore.storage.migrations.Migration_28_29
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class OrphanBlockDaoTest {

    private lateinit var database: CoreDatabase
    private lateinit var orphanBlockDao: OrphanBlockDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CoreDatabase::class.java
        )
            .addMigrations(Migration_28_29)
            .setTransactionExecutor(Executors.newSingleThreadExecutor())
            .setQueryExecutor(Executors.newSingleThreadExecutor())
            .allowMainThreadQueries()
            .build()

        orphanBlockDao = database.orphanBlockDao
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insert and retrieve OrphanBlock preserves all fields`() {
        // Arrange
        val originalBlock = createTestOrphanBlock()

        // Act
        orphanBlockDao.insert(originalBlock)
        val retrievedBlock = orphanBlockDao.getOrphanBlockByHash(originalBlock.headerHash)

        // Assert
        assertNotNull("Retrieved block should not be null", retrievedBlock)
        assertEquals("Version should be preserved", originalBlock.version, retrievedBlock!!.version)
        assertArrayEquals("Previous block hash should be preserved", 
            originalBlock.previousBlockHash, retrievedBlock.previousBlockHash)
        assertArrayEquals("Merkle root should be preserved", 
            originalBlock.merkleRoot, retrievedBlock.merkleRoot)
        assertEquals("Timestamp should be preserved", 
            originalBlock.timestamp, retrievedBlock.timestamp)
        assertEquals("Bits should be preserved", 
            originalBlock.bits, retrievedBlock.bits)
        assertEquals("Nonce should be preserved", 
            originalBlock.nonce, retrievedBlock.nonce)
        assertEquals("Has transactions should be preserved", 
            originalBlock.hasTransactions, retrievedBlock.hasTransactions)
        assertArrayEquals("Header hash should be preserved", 
            originalBlock.headerHash, retrievedBlock.headerHash)
    }

    @Test
    fun `insert and retrieve multiple OrphanBlocks preserves all fields`() {
        // Arrange
        val blocks = listOf(
            createTestOrphanBlock(version = 1, timestamp = 1000L, headerHash = byteArrayOf(1)),
            createTestOrphanBlock(version = 2, timestamp = 2000L, headerHash = byteArrayOf(2)),
            createTestOrphanBlock(version = 3, timestamp = 3000L, headerHash = byteArrayOf(3))
        )

        // Act
        blocks.forEach { orphanBlockDao.insert(it) }
        val retrievedBlocks = orphanBlockDao.getOrphanBlocks()

        // Assert
        assertEquals("Should retrieve all inserted blocks", blocks.size, retrievedBlocks.size)
        
        blocks.forEach { originalBlock ->
            val retrievedBlock = retrievedBlocks.find { 
                it.headerHash.contentEquals(originalBlock.headerHash) 
            }
            assertNotNull("Block should be found", retrievedBlock)
            assertOrphanBlockFieldsEqual(originalBlock, retrievedBlock!!)
        }
    }

    @Test
    fun `getOrphanChild returns correct child block`() {
        // Arrange
        val parentHash = byteArrayOf(1, 2, 3, 4)
        val childBlock = createTestOrphanBlock(previousBlockHash = parentHash, headerHash = byteArrayOf(1))
        val otherBlock = createTestOrphanBlock(previousBlockHash = byteArrayOf(5, 6, 7, 8), headerHash = byteArrayOf(2))

        // Act
        orphanBlockDao.insert(childBlock)
        orphanBlockDao.insert(otherBlock)
        val retrievedChild = orphanBlockDao.getOrphanChild(parentHash)

        // Assert
        assertNotNull("Child block should be found", retrievedChild)
        assertArrayEquals("Should return correct child block", 
            childBlock.headerHash, retrievedChild!!.headerHash)
        assertOrphanBlockFieldsEqual(childBlock, retrievedChild)
    }

    @Test
    fun `delete removes block from database`() {
        // Arrange
        val block = createTestOrphanBlock()
        orphanBlockDao.insert(block)

        // Act
        orphanBlockDao.delete(block)
        val retrievedBlock = orphanBlockDao.getOrphanBlockByHash(block.headerHash)

        // Assert
        assertNull("Block should be deleted", retrievedBlock)
    }

    @Test
    fun `deleteAll removes multiple blocks from database`() {
        // Arrange
        val blocks = listOf(
            createTestOrphanBlock(version = 1),
            createTestOrphanBlock(version = 2),
            createTestOrphanBlock(version = 3)
        )
        blocks.forEach { orphanBlockDao.insert(it) }

        // Act
        orphanBlockDao.deleteAll(blocks)
        val retrievedBlocks = orphanBlockDao.getOrphanBlocks()

        // Assert
        assertTrue("All blocks should be deleted", retrievedBlocks.isEmpty())
    }

    @Test
    fun `insert with duplicate headerHash replaces existing block`() {
        // Arrange
        val originalBlock = createTestOrphanBlock(version = 1, timestamp = 1000L)
        val replacementBlock = createTestOrphanBlock(
            headerHash = originalBlock.headerHash,
            version = 2, 
            timestamp = 2000L
        )

        // Act
        orphanBlockDao.insert(originalBlock)
        orphanBlockDao.insert(replacementBlock)
        val retrievedBlock = orphanBlockDao.getOrphanBlockByHash(originalBlock.headerHash)

        // Assert
        assertNotNull("Block should exist", retrievedBlock)
        assertEquals("Version should be updated", 2, retrievedBlock!!.version)
        assertEquals("Timestamp should be updated", 2000L, retrievedBlock.timestamp)
    }

    @Test
    fun `handles empty ByteArray fields correctly`() {
        // Arrange
        val block = OrphanBlock().apply {
            version = 1
            previousBlockHash = byteArrayOf()
            merkleRoot = byteArrayOf()
            timestamp = 1000L
            bits = 12345L
            nonce = 67890L
            hasTransactions = false
            headerHash = byteArrayOf(1, 2, 3, 4)
        }

        // Act
        orphanBlockDao.insert(block)
        val retrievedBlock = orphanBlockDao.getOrphanBlockByHash(block.headerHash)

        // Assert
        assertNotNull("Block should be retrieved", retrievedBlock)
        assertArrayEquals("Empty previous block hash should be preserved", 
            byteArrayOf(), retrievedBlock!!.previousBlockHash)
        assertArrayEquals("Empty merkle root should be preserved", 
            byteArrayOf(), retrievedBlock.merkleRoot)
    }

    @Test
    fun `handles maximum values correctly`() {
        // Arrange
        val block = OrphanBlock().apply {
            version = Int.MAX_VALUE
            previousBlockHash = ByteArray(32) { 0xFF.toByte() }
            merkleRoot = ByteArray(32) { 0xFF.toByte() }
            timestamp = Long.MAX_VALUE
            bits = Long.MAX_VALUE
            nonce = Long.MAX_VALUE
            hasTransactions = true
            headerHash = ByteArray(32) { 0xFF.toByte() }
        }

        // Act
        orphanBlockDao.insert(block)
        val retrievedBlock = orphanBlockDao.getOrphanBlockByHash(block.headerHash)

        // Assert
        assertNotNull("Block should be retrieved", retrievedBlock)
        assertEquals("Max version should be preserved", Int.MAX_VALUE, retrievedBlock!!.version)
        assertEquals("Max timestamp should be preserved", Long.MAX_VALUE, retrievedBlock.timestamp)
        assertEquals("Max bits should be preserved", Long.MAX_VALUE, retrievedBlock.bits)
        assertEquals("Max nonce should be preserved", Long.MAX_VALUE, retrievedBlock.nonce)
        assertTrue("Has transactions should be preserved", retrievedBlock.hasTransactions)
    }

    private fun createTestOrphanBlock(
        version: Int = 1,
        previousBlockHash: ByteArray = byteArrayOf(1, 2, 3, 4),
        merkleRoot: ByteArray = byteArrayOf(5, 6, 7, 8),
        timestamp: Long = 1000L,
        bits: Long = 12345L,
        nonce: Long = 67890L,
        hasTransactions: Boolean = true,
        headerHash: ByteArray = byteArrayOf(9, 10, 11, 12)
    ): OrphanBlock {
        return OrphanBlock().apply {
            this.version = version
            this.previousBlockHash = previousBlockHash
            this.merkleRoot = merkleRoot
            this.timestamp = timestamp
            this.bits = bits
            this.nonce = nonce
            this.hasTransactions = hasTransactions
            this.headerHash = headerHash
        }
    }

    private fun assertOrphanBlockFieldsEqual(expected: OrphanBlock, actual: OrphanBlock) {
        assertEquals("Version should match", expected.version, actual.version)
        assertArrayEquals("Previous block hash should match", 
            expected.previousBlockHash, actual.previousBlockHash)
        assertArrayEquals("Merkle root should match", 
            expected.merkleRoot, actual.merkleRoot)
        assertEquals("Timestamp should match", expected.timestamp, actual.timestamp)
        assertEquals("Bits should match", expected.bits, actual.bits)
        assertEquals("Nonce should match", expected.nonce, actual.nonce)
        assertEquals("Has transactions should match", 
            expected.hasTransactions, actual.hasTransactions)
        assertArrayEquals("Header hash should match", 
            expected.headerHash, actual.headerHash)
    }
}

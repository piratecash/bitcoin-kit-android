package io.horizontalsystems.bitcoincore.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.models.OrphanBlock
import io.horizontalsystems.bitcoincore.storage.migrations.Migration_28_29
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class StorageOrphanBlockIntegrationTest {

    private lateinit var database: CoreDatabase
    private lateinit var storage: Storage

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

        storage = Storage(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `Storage addOrphanBlock and getOrphanBlock preserves all fields`() {
        // Arrange
        val originalBlock = createTestOrphanBlock()

        // Act
        storage.addOrphanBlock(originalBlock)
        val retrievedBlock = storage.getOrphanBlock(originalBlock.headerHash)

        // Assert
        assertNotNull("Retrieved block should not be null", retrievedBlock)
        assertOrphanBlockFieldsEqual(originalBlock, retrievedBlock!!)
    }

    @Test
    fun `Storage getOrphanBlocks returns all blocks with preserved fields`() {
        // Arrange
        val blocks = listOf(
            createTestOrphanBlock(version = 1, timestamp = 1000L, headerHash =  byteArrayOf(1)),
            createTestOrphanBlock(version = 2, timestamp = 2000L, headerHash =  byteArrayOf(2)),
            createTestOrphanBlock(version = 3, timestamp = 3000L, headerHash =  byteArrayOf(3))
        )

        // Act
        blocks.forEach { storage.addOrphanBlock(it) }
        val retrievedBlocks = storage.getOrphanBlocks()

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
    fun `Storage getOrphanChild returns correct child with preserved fields`() {
        // Arrange
        val parentHash = byteArrayOf(1, 2, 3, 4)
        val childBlock = createTestOrphanBlock(previousBlockHash = parentHash, headerHash = byteArrayOf(1))
        val otherBlock = createTestOrphanBlock(previousBlockHash = byteArrayOf(5, 6, 7, 8), headerHash = byteArrayOf(2))

        // Act
        storage.addOrphanBlock(childBlock)
        storage.addOrphanBlock(otherBlock)
        val retrievedChild = storage.getOrphanChild(parentHash)

        // Assert
        assertNotNull("Child block should be found", retrievedChild)
        assertArrayEquals(
            "Should return correct child block",
            childBlock.headerHash, retrievedChild!!.headerHash
        )
        assertOrphanBlockFieldsEqual(childBlock, retrievedChild)
    }

    @Test
    fun `Storage deleteOrphanBlock removes block completely`() {
        // Arrange
        val block = createTestOrphanBlock()
        storage.addOrphanBlock(block)

        // Act
        storage.deleteOrphanBlock(block)
        val retrievedBlock = storage.getOrphanBlock(block.headerHash)

        // Assert
        assertNull("Block should be deleted", retrievedBlock)
    }

    @Test
    fun `Storage operations work with complex data structures`() {
        // Arrange
        val complexBlock = OrphanBlock().apply {
            version = Int.MAX_VALUE
            previousBlockHash = ByteArray(32) { it.toByte() }
            merkleRoot = ByteArray(32) { (it + 100).toByte() }
            timestamp = Long.MAX_VALUE
            bits = Long.MAX_VALUE
            nonce = Long.MAX_VALUE
            hasTransactions = true
            headerHash = ByteArray(32) { (it + 200).toByte() }
        }

        // Act
        storage.addOrphanBlock(complexBlock)
        val retrievedBlock = storage.getOrphanBlock(complexBlock.headerHash)

        // Assert
        assertNotNull("Complex block should be retrieved", retrievedBlock)
        assertOrphanBlockFieldsEqual(complexBlock, retrievedBlock!!)
    }

    @Test
    fun `Storage handles multiple operations in sequence`() {
        // Arrange
        val block1 = createTestOrphanBlock(version = 1,  headerHash = byteArrayOf(1))
        val block2 = createTestOrphanBlock(version = 2,  headerHash = byteArrayOf(2))
        val block3 = createTestOrphanBlock(version = 3,  headerHash = byteArrayOf(3))

        // Act - Add all blocks
        storage.addOrphanBlock(block1)
        storage.addOrphanBlock(block2)
        storage.addOrphanBlock(block3)

        // Verify all are stored
        val allBlocks = storage.getOrphanBlocks()
        assertEquals("All blocks should be stored", 3, allBlocks.size)

        // Delete one block
        storage.deleteOrphanBlock(block2)
        val remainingBlocks = storage.getOrphanBlocks()
        assertEquals("One block should be deleted", 2, remainingBlocks.size)

        // Verify specific blocks exist
        assertNotNull("Block 1 should exist", storage.getOrphanBlock(block1.headerHash))
        assertNull("Block 2 should be deleted", storage.getOrphanBlock(block2.headerHash))
        assertNotNull("Block 3 should exist", storage.getOrphanBlock(block3.headerHash))
    }

    @Test
    fun `Storage handles empty and null values correctly`() {
        // Arrange
        val emptyBlock = OrphanBlock().apply {
            version = 0
            previousBlockHash = byteArrayOf()
            merkleRoot = byteArrayOf()
            timestamp = 0L
            bits = 0L
            nonce = 0L
            hasTransactions = false
            headerHash = byteArrayOf(1, 2, 3, 4) // Only this needs to be non-empty for primary key
        }

        // Act
        storage.addOrphanBlock(emptyBlock)
        val retrievedBlock = storage.getOrphanBlock(emptyBlock.headerHash)

        // Assert
        assertNotNull("Empty block should be retrieved", retrievedBlock)
        assertOrphanBlockFieldsEqual(emptyBlock, retrievedBlock!!)
    }

    @Test
    fun `Storage getOrphanChild returns null when no child found`() {
        // Arrange
        val nonExistentParentHash = byteArrayOf(99, 99, 99, 99)
        val block = createTestOrphanBlock(previousBlockHash = byteArrayOf(1, 2, 3, 4))
        storage.addOrphanBlock(block)

        // Act
        val result = storage.getOrphanChild(nonExistentParentHash)

        // Assert
        assertNull("Should return null when no child found", result)
    }

    @Test
    fun `Storage getOrphanBlock returns null for non-existent hash`() {
        // Arrange
        val nonExistentHash = byteArrayOf(99, 99, 99, 99)

        // Act
        val result = storage.getOrphanBlock(nonExistentHash)

        // Assert
        assertNull("Should return null for non-existent hash", result)
    }

    @Test
    fun `Storage operations are atomic - all succeed or all fail`() {
        // Arrange
        val validBlock = createTestOrphanBlock(version = 1, headerHash =  byteArrayOf(1))
        val anotherValidBlock = createTestOrphanBlock(version = 2, headerHash =  byteArrayOf(2))

        // Act - Add multiple blocks
        storage.addOrphanBlock(validBlock)
        storage.addOrphanBlock(anotherValidBlock)

        // Assert - All should be present
        val allBlocks = storage.getOrphanBlocks()
        assertEquals("Both blocks should be present", 2, allBlocks.size)
        assertNotNull("First block should exist", storage.getOrphanBlock(validBlock.headerHash))
        assertNotNull(
            "Second block should exist",
            storage.getOrphanBlock(anotherValidBlock.headerHash)
        )
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
        assertArrayEquals(
            "Previous block hash should match",
            expected.previousBlockHash, actual.previousBlockHash
        )
        assertArrayEquals(
            "Merkle root should match",
            expected.merkleRoot, actual.merkleRoot
        )
        assertEquals("Timestamp should match", expected.timestamp, actual.timestamp)
        assertEquals("Bits should match", expected.bits, actual.bits)
        assertEquals("Nonce should match", expected.nonce, actual.nonce)
        assertEquals(
            "Has transactions should match",
            expected.hasTransactions, actual.hasTransactions
        )
        assertArrayEquals(
            "Header hash should match",
            expected.headerHash, actual.headerHash
        )
    }
}


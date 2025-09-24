package io.horizontalsystems.bitcoincore.storage

import io.horizontalsystems.bitcoincore.models.OrphanBlock
import org.junit.Assert.*
import org.junit.Test

class SimpleOrphanBlockTest {

    @Test
    fun `test OrphanBlock creation and field preservation`() {
        // Arrange
        val headerHash = byteArrayOf(1, 2, 3, 4, 5)
        val previousBlockHash = byteArrayOf(6, 7, 8, 9, 10)
        val merkleRoot = byteArrayOf(11, 12, 13, 14, 15)
        val version = 1
        val timestamp = 1234567890L
        val bits = 0x1d00ffffL
        val nonce = 12345L

        // Act
        val orphanBlock = OrphanBlock().apply {
            this.headerHash = headerHash
            this.previousBlockHash = previousBlockHash
            this.merkleRoot = merkleRoot
            this.version = version
            this.timestamp = timestamp
            this.bits = bits
            this.nonce = nonce
        }

        // Assert
        assertNotNull("OrphanBlock should not be null", orphanBlock)
        assertArrayEquals("Header hash should match", headerHash, orphanBlock.headerHash)
        assertArrayEquals("Previous block hash should match", previousBlockHash, orphanBlock.previousBlockHash)
        assertArrayEquals("Merkle root should match", merkleRoot, orphanBlock.merkleRoot)
        assertEquals("Version should match", version, orphanBlock.version)
        assertEquals("Timestamp should match", timestamp, orphanBlock.timestamp)
        assertEquals("Bits should match", bits, orphanBlock.bits)
        assertEquals("Nonce should match", nonce, orphanBlock.nonce)
    }

    @Test
    fun `test OrphanBlock with null merkleBlock`() {
        // Arrange
        val headerHash = byteArrayOf(1, 2, 3, 4, 5)
        val previousBlockHash = byteArrayOf(6, 7, 8, 9, 10)
        val merkleRoot = byteArrayOf(11, 12, 13, 14, 15)
        val version = 1
        val timestamp = 1234567890L
        val bits = 0x1d00ffffL
        val nonce = 12345L

        // Act
        val orphanBlock = OrphanBlock().apply {
            this.headerHash = headerHash
            this.previousBlockHash = previousBlockHash
            this.merkleRoot = merkleRoot
            this.version = version
            this.timestamp = timestamp
            this.bits = bits
            this.nonce = nonce
            this.merkleBlock = null
        }

        // Assert
        assertNotNull("OrphanBlock should not be null", orphanBlock)
        assertArrayEquals("Header hash should match", headerHash, orphanBlock.headerHash)
        assertArrayEquals("Previous block hash should match", previousBlockHash, orphanBlock.previousBlockHash)
        assertArrayEquals("Merkle root should match", merkleRoot, orphanBlock.merkleRoot)
        assertEquals("Version should match", version, orphanBlock.version)
        assertEquals("Timestamp should match", timestamp, orphanBlock.timestamp)
        assertEquals("Bits should match", bits, orphanBlock.bits)
        assertEquals("Nonce should match", nonce, orphanBlock.nonce)
        assertNull("Merkle block should be null", orphanBlock.merkleBlock)
    }

    @Test
    fun `test OrphanBlock with empty arrays`() {
        // Arrange
        val emptyArray = byteArrayOf()

        // Act
        val orphanBlock = OrphanBlock().apply {
            this.headerHash = emptyArray
            this.previousBlockHash = emptyArray
            this.merkleRoot = emptyArray
            this.version = 0
            this.timestamp = 0L
            this.bits = 0L
            this.nonce = 0L
        }

        // Assert
        assertNotNull("OrphanBlock should not be null", orphanBlock)
        assertArrayEquals("Header hash should be empty", emptyArray, orphanBlock.headerHash)
        assertArrayEquals("Previous block hash should be empty", emptyArray, orphanBlock.previousBlockHash)
        assertArrayEquals("Merkle root should be empty", emptyArray, orphanBlock.merkleRoot)
        assertEquals("Version should be 0", 0, orphanBlock.version)
        assertEquals("Timestamp should be 0", 0L, orphanBlock.timestamp)
        assertEquals("Bits should be 0", 0L, orphanBlock.bits)
        assertEquals("Nonce should be 0", 0L, orphanBlock.nonce)
    }

    @Test
    fun `test OrphanBlock with maximum values`() {
        // Arrange
        val maxArray = ByteArray(32) { Byte.MAX_VALUE }
        val maxInt = Int.MAX_VALUE
        val maxLong = Long.MAX_VALUE

        // Act
        val orphanBlock = OrphanBlock().apply {
            this.headerHash = maxArray
            this.previousBlockHash = maxArray
            this.merkleRoot = maxArray
            this.version = maxInt
            this.timestamp = maxLong
            this.bits = maxLong
            this.nonce = maxLong
        }

        // Assert
        assertNotNull("OrphanBlock should not be null", orphanBlock)
        assertArrayEquals("Header hash should match max array", maxArray, orphanBlock.headerHash)
        assertArrayEquals("Previous block hash should match max array", maxArray, orphanBlock.previousBlockHash)
        assertArrayEquals("Merkle root should match max array", maxArray, orphanBlock.merkleRoot)
        assertEquals("Version should be max int", maxInt, orphanBlock.version)
        assertEquals("Timestamp should be max long", maxLong, orphanBlock.timestamp)
        assertEquals("Bits should be max long", maxLong, orphanBlock.bits)
        assertEquals("Nonce should be max long", maxLong, orphanBlock.nonce)
    }
}

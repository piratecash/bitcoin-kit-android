package io.horizontalsystems.bitcoincore.storage

import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.horizontalsystems.bitcoincore.models.PeerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class CoreDatabaseSqlCipherJvmTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun roomDatabase_rawKey_createsEncryptedDatabaseAndServesDao() {
        val directory = Files.createTempDirectory("bitcoincore-sqlcipher")
        val path = directory.resolve("core.db")
        val database = CoreDatabase.getInstance(directory.toString(), path.fileName.toString(), key)
        try {
            database.peerAddress.insertAll(listOf(PeerAddress("10.0.0.1")))
            assertEquals("10.0.0.1", database.peerAddress.getLeastScoreFastest(emptyList())?.ip)
        } finally {
            database.close()
        }

        val header = Files.readAllBytes(path).copyOfRange(0, 16)
        assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray()))

        assertThrows(DatabaseKeyMismatchException::class.java) {
            CoreDatabase.getInstance(directory.toString(), path.fileName.toString(), ByteArray(32) { 1 })
        }
    }

    @Test
    fun getInstance_plaintextDatabaseWithKey_requiresMigration() {
        val directory = Files.createTempDirectory("bitcoincore-plaintext")
        val path = directory.resolve("core.db")
        BundledSQLiteDriver().open(path.toString()).use { it.execSQL("PRAGMA user_version=33") }

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            CoreDatabase.getInstance(directory.toString(), path.fileName.toString(), key)
        }
    }

    @Test
    fun getInstance_encryptedDatabaseWithoutKey_requiresKey() {
        val directory = Files.createTempDirectory("bitcoincore-encrypted")
        val path = directory.resolve("core.db")
        val database = CoreDatabase.getInstance(directory.toString(), path.fileName.toString(), key)
        try {
            database.peerAddress.hasFresh(emptyList())
        } finally {
            database.close()
        }

        assertThrows(DatabaseKeyRequiredException::class.java) {
            CoreDatabase.getInstance(directory.toString(), path.fileName.toString())
        }
    }
}

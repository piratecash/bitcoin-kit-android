package io.horizontalsystems.bitcoincore.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.horizontalsystems.databasenaming.DatabaseNameResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DatabaseEncryptionNameResolverJvmTest {
    private val key = ByteArray(32) { it.toByte() }
    private val resolver = DatabaseNameResolver { "opaque-$it" }

    @Test
    fun migrateDatabases_overrideResolver_encryptsResolvedFileAndUsesResolvedLock() = runBlocking {
        val dir = tempDir()
        createPlaintextDatabase(File(dir, "opaque-core.db"))

        val result = DatabaseEncryption.migrateDatabases(dir.path, listOf("core.db"), "wallet", key, resolver)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertEquals(setOf("opaque-core.db", ".opaque-bitcoin-kit-sqlcipher.lock"), dir.list().orEmpty().toSet())
    }

    @Test
    fun migrateDatabases_overrideResolver_ignoresFileUnderDefaultName() = runBlocking {
        val dir = tempDir()
        createPlaintextDatabase(File(dir, "core.db"))

        val result = DatabaseEncryption.migrateDatabases(dir.path, listOf("core.db"), "wallet", key, resolver)

        assertEquals(DatabaseMigrationResult(0, 0), result)
    }

    @Test
    fun clearDatabases_overrideResolver_removesResolvedFamilyAndKeepsDefaultFile() {
        val dir = tempDir()
        val family = listOf("opaque-core.db", "opaque-core.db-wal", "opaque-core.db-shm").map { File(dir, it) }
        family.forEach { it.writeText("db") }
        val foreign = File(dir, "core.db").apply { writeText("db") }

        DatabaseEncryption.clearDatabases(dir.path, listOf("core.db"), "wallet", resolver)

        family.forEach { assertFalse(it.name, it.exists()) }
        assertTrue(foreign.exists())
    }

    @Test
    fun deleteDatabaseFiles_overrideResolver_removesResolvedFile() {
        val dir = tempDir()
        val resolved = File(dir, "opaque-core.db").apply { writeText("db") }
        val foreign = File(dir, "core.db").apply { writeText("db") }

        deleteDatabaseFiles(dir.path, "core.db", resolver)

        assertFalse(resolved.exists())
        assertTrue(foreign.exists())
    }

    @Test
    fun getInstance_overrideResolver_writesOnlyResolvedFile() {
        val dir = tempDir()
        val database = CoreDatabase.getInstance(dir.path, "core.db", key, resolver)
        try {
            database.peerAddress.hasFresh(emptyList())
        } finally {
            database.close()
        }

        assertTrue(File(dir, "opaque-core.db").isFile)
        assertFalse(File(dir, "core.db").exists())
    }

    @Test
    fun getInstance_pendingManifestOfResolvedNamespace_throwsMigrationInProgress() {
        val dir = tempDir()
        File(dir, ".opaque-bitcoin-kit-sqlcipher-e8d44050873dba86.json").writeText("{}")

        assertThrows(DatabaseMigrationInProgressException::class.java) {
            CoreDatabase.getInstance(dir.path, "core.db", key, resolver)
        }
    }

    @Test
    fun getInstance_pendingManifestOfDefaultNamespace_isNotSeenUnderOverride() {
        val dir = tempDir()
        File(dir, ".bitcoin-kit-sqlcipher-e8d44050873dba86.json").writeText("{}")

        val database = CoreDatabase.getInstance(dir.path, "core.db", key, resolver)

        database.close()
    }

    private fun tempDir(): File = Files.createTempDirectory("database-name-resolver").toFile()

    private fun createPlaintextDatabase(file: File) {
        BundledSQLiteDriver().open(file.path).use { connection ->
            connection.execSQL("CREATE TABLE sample(value TEXT NOT NULL)")
        }
    }
}

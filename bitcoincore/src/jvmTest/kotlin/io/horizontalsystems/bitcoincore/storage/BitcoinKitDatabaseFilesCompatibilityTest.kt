package io.horizontalsystems.bitcoincore.storage

import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.horizontalsystems.sqlcipher.SqlCipherDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class BitcoinKitDatabaseFilesCompatibilityTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun migrateDatabases_interruptedManifestWrittenByPreviousRelease_recoversAndEncrypts() = runBlocking {
        val directory = Files.createTempDirectory("bitcoin-kit-compat-manifest")
        val database = directory.resolve("core.db")
        val staging = directory.resolve("core.db.sqlcipher-migrating")
        createPlaintextDatabase(database, "core")
        Files.writeString(staging, "partial")
        // Exact bytes and file name a pcash.35 wallet leaves behind for migrationId "wallet".
        val manifest = Files.writeString(
            directory.resolve(".bitcoin-kit-sqlcipher-e8d44050873dba86.json"),
            """{"version":1,"phase":"STAGED","entries":[{"databasePath":"$database","stagingPath":"$staging"}]}""",
        )

        val result = migrate(directory)

        assertEquals(1, result.migratedDatabaseCount)
        assertFalse(Files.exists(manifest))
        assertFalse(Files.exists(staging))
        assertEquals("core", readEncryptedValue(database))
    }

    @Test
    fun migrateDatabases_bitcoinKitGroup_usesPreviousLockFileName() = runBlocking {
        val directory = Files.createTempDirectory("bitcoin-kit-compat-lock")
        createPlaintextDatabase(directory.resolve("core.db"), "core")

        migrate(directory)

        val lockFiles = Files.list(directory).use { files ->
            files.map { it.fileName.toString() }.filter { it.endsWith(".lock") }.toList()
        }
        assertEquals(listOf(".bitcoin-kit-sqlcipher.lock"), lockFiles)
    }

    @Test
    fun coreDatabaseBuilder_manifestWrittenByPreviousRelease_throwsMigrationInProgress() {
        val directory = Files.createTempDirectory("bitcoin-kit-compat-pending")
        Files.writeString(directory.resolve(".bitcoin-kit-sqlcipher-x.json"), "{}")

        assertThrows(DatabaseMigrationInProgressException::class.java) {
            coreDatabaseBuilder(directory.resolve("core.db").toString())
        }
    }

    private suspend fun migrate(directory: Path): DatabaseMigrationResult = DatabaseEncryption.migrateDatabases(
        dataDir = directory.toString(),
        databaseNames = listOf("core.db"),
        migrationId = "wallet",
        databaseKey = key,
    )

    private fun createPlaintextDatabase(path: Path, value: String) {
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            connection.execSQL("CREATE TABLE sample(value TEXT NOT NULL)")
            connection.execSQL("INSERT INTO sample VALUES('$value')")
        }
    }

    private fun readEncryptedValue(path: Path): String = SqlCipherDriver(key).use { driver ->
        driver.open(path.toString()).use { connection ->
            connection.prepare("SELECT value FROM sample").use { statement ->
                check(statement.step()) { "Test database contains no sample row" }
                statement.getText(0)
            }
        }
    }
}

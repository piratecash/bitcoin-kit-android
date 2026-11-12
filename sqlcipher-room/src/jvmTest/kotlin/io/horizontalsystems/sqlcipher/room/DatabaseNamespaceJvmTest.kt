package io.horizontalsystems.sqlcipher.room

import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.horizontalsystems.sqlcipher.SqlCipherDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DatabaseNamespaceJvmTest {
    private val key = ByteArray(32) { it.toByte() }
    private val databases = SqlCipherDatabases("test-kit")
    private val otherDatabases = SqlCipherDatabases("other-kit")

    @Test
    fun migrateDatabases_otherNamespaceManifestInSameDirectory_leavesItUntouched() = runBlocking {
        val directory = Files.createTempDirectory("database-namespace-manifest")
        createPlaintextDatabase(directory.resolve("core.db"))
        val otherManifest = Files.writeString(directory.resolve(".other-kit-sqlcipher-x.json"), "not-json")

        val result = migrate(directory)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertTrue(Files.exists(otherManifest))
        assertEquals("core", readEncryptedValue(directory.resolve("core.db")))
    }

    @Test
    fun migrateDatabases_otherNamespaceHoldsLock_proceeds() {
        val directory = Files.createTempDirectory("database-namespace-lock")
        createPlaintextDatabase(directory.resolve("core.db"))

        val result = withDatabaseMigrationLock(directory.toFile(), otherDatabases.fileNames) {
            runBlocking { migrate(directory) }
        }

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertEquals("core", readEncryptedValue(directory.resolve("core.db")))
    }

    private suspend fun migrate(directory: Path): DatabaseMigrationResult = databases.migrateDatabases(
        dataDir = directory.toString(),
        databaseNames = listOf("core.db"),
        migrationId = "wallet",
        databaseKey = key,
    )

    private fun createPlaintextDatabase(path: Path) {
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            connection.execSQL("CREATE TABLE sample(value TEXT NOT NULL)")
            connection.execSQL("INSERT INTO sample VALUES('core')")
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

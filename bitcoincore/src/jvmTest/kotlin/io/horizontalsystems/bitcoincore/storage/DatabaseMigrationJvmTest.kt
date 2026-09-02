package io.horizontalsystems.bitcoincore.storage

import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.horizontalsystems.sqlcipher.SqlCipherDriver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class DatabaseMigrationJvmTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun migrateDatabases_plaintextGroup_encryptsEveryDatabaseAndPreservesData() = runBlocking {
        val directory = Files.createTempDirectory("database-migration")
        createPlaintextDatabase(directory.resolve("core.db"), "core")
        createPlaintextDatabase(directory.resolve("coin.db"), "coin")

        val result = DatabaseEncryption.migrateDatabases(
            dataDir = directory.toString(),
            databaseNames = listOf("core.db", "coin.db"),
            migrationId = "wallet",
            databaseKey = key,
        )

        assertEquals(DatabaseMigrationResult(2, 0), result)
        assertEquals("core", readEncryptedValue(directory.resolve("core.db")))
        assertEquals("coin", readEncryptedValue(directory.resolve("coin.db")))
        assertTrue(Files.list(directory).use { files -> files.noneMatch(::isMigrationArtifact) })
    }

    @Test
    fun migrateDatabases_alreadyEncryptedGroup_keepsFilesAndReportsCount() = runBlocking {
        val directory = Files.createTempDirectory("database-migration-encrypted")
        createEncryptedDatabase(directory.resolve("core.db"), "core")

        val result = DatabaseEncryption.migrateDatabases(
            dataDir = directory.toString(),
            databaseNames = listOf("core.db", "missing.db"),
            migrationId = "wallet",
            databaseKey = key,
        )

        assertEquals(DatabaseMigrationResult(0, 1), result)
        assertEquals("core", readEncryptedValue(directory.resolve("core.db")))
    }

    @Test
    fun migrateDatabases_mixedGroup_rejectsWithoutChangingEitherDatabase() {
        val directory = Files.createTempDirectory("database-migration-mixed")
        val plaintext = directory.resolve("plain.db")
        val encrypted = directory.resolve("encrypted.db")
        createPlaintextDatabase(plaintext, "plain")
        createEncryptedDatabase(encrypted, "encrypted")

        assertThrows(DatabaseMigrationConflictException::class.java) {
            runBlocking {
                DatabaseEncryption.migrateDatabases(
                    dataDir = directory.toString(),
                    databaseNames = listOf("plain.db", "encrypted.db"),
                    migrationId = "wallet",
                    databaseKey = key,
                )
            }
        }

        assertTrue(Files.readAllBytes(plaintext).copyOfRange(0, 16).contentEquals(SQLITE_HEADER))
        assertEquals("encrypted", readEncryptedValue(encrypted))
        assertFalse(Files.list(directory).use { files -> files.anyMatch(::isMigrationArtifact) })
    }

    @Test
    fun migrateDatabases_stagedMigrationWasInterrupted_recoversAndMigratesWholeGroup() = runBlocking {
        val directory = Files.createTempDirectory("database-migration-staged")
        val core = directory.resolve("core.db")
        val coin = directory.resolve("coin.db")
        createPlaintextDatabase(core, "core")
        createPlaintextDatabase(coin, "coin")
        val entries = listOf(core, coin).map(::stagePlaintextDatabase)
        installStagedDatabase(entries.first())
        writeManifest(directory, MigrationPhase.STAGED, entries)

        val result = migrate(directory, listOf("core.db", "coin.db"))

        assertEquals(DatabaseMigrationResult(2, 0), result)
        assertEquals("core", readEncryptedValue(core))
        assertEquals("coin", readEncryptedValue(coin))
        assertTrue(Files.list(directory).use { files -> files.noneMatch(::isMigrationArtifact) })
    }

    @Test
    fun migrateDatabases_committedMigrationWasInterrupted_finishesCleanupWithoutRewritingDatabase() = runBlocking {
        val directory = Files.createTempDirectory("database-migration-committed")
        val database = directory.resolve("core.db")
        createPlaintextDatabase(database, "core")
        val entry = stagePlaintextDatabase(database)
        installStagedDatabase(entry)
        writeManifest(directory, MigrationPhase.COMMITTED, listOf(entry))

        val result = migrate(directory, listOf("core.db"))

        assertEquals(DatabaseMigrationResult(0, 1), result)
        assertEquals("core", readEncryptedValue(database))
        assertTrue(Files.list(directory).use { files -> files.noneMatch(::isMigrationArtifact) })
    }

    @Test
    fun clearDatabases_stagedMigrationWasInterrupted_removesWalletWithoutRestoringPlaintext() = runBlocking {
        val directory = Files.createTempDirectory("database-clear-staged")
        val core = directory.resolve("core.db")
        val coin = directory.resolve("coin.db")
        createPlaintextDatabase(core, "core")
        createPlaintextDatabase(coin, "coin")
        val entries = listOf(core, coin).map(::stagePlaintextDatabase)
        installStagedDatabase(entries.first())
        writeManifest(directory, MigrationPhase.STAGED, entries)

        DatabaseEncryption.clearDatabases(
            dataDir = directory.toString(),
            databaseNames = listOf("core.db", "coin.db"),
            migrationId = "wallet",
        )

        assertFalse(Files.exists(core))
        assertFalse(Files.exists(coin))
        assertEquals(DatabaseMigrationResult(0, 0), migrate(directory, listOf("core.db", "coin.db")))
        assertTrue(Files.list(directory).use { files -> files.noneMatch(::isMigrationArtifact) })
    }

    @Test
    fun migrateDatabases_clearingWasInterrupted_finishesDeletionBeforeMigration() = runBlocking {
        val directory = Files.createTempDirectory("database-clear-recovery")
        val database = directory.resolve("core.db")
        createPlaintextDatabase(database, "core")
        val entry = stagePlaintextDatabase(database)
        installStagedDatabase(entry)
        writeManifest(directory, MigrationPhase.CLEARING, listOf(entry))

        val result = migrate(directory, listOf("core.db"))

        assertEquals(DatabaseMigrationResult(0, 0), result)
        assertFalse(Files.exists(database))
        assertTrue(Files.list(directory).use { files -> files.noneMatch(::isMigrationArtifact) })
    }

    @Test
    fun migrateDatabases_encryptedDatabaseUsesDifferentKey_throwsTypedErrorWithoutChangingDatabase() {
        val directory = Files.createTempDirectory("database-migration-wrong-key")
        val database = directory.resolve("core.db")
        createEncryptedDatabase(database, "core")

        assertThrows(DatabaseKeyMismatchException::class.java) {
            runBlocking {
                DatabaseEncryption.migrateDatabases(
                    dataDir = directory.toString(),
                    databaseNames = listOf("core.db"),
                    migrationId = "wallet",
                    databaseKey = ByteArray(32) { (it + 1).toByte() },
                )
            }
        }

        assertEquals("core", readEncryptedValue(database))
    }

    @Test
    fun migrateDatabases_unreadableManifest_discardsManifestAndEncryptsDatabase() = runBlocking {
        val directory = Files.createTempDirectory("database-migration-invalid-manifest")
        val database = directory.resolve("core.db")
        createPlaintextDatabase(database, "core")
        val manifest = writeUnreadableManifest(directory)

        val result = migrate(directory, listOf("core.db"))

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(Files.exists(manifest))
        assertEquals("core", readEncryptedValue(database))
    }

    @Test
    fun migrateDatabases_unreadableManifestOverOwnInterruptedMigration_restoresBackupThenMigrates() = runBlocking {
        val directory = Files.createTempDirectory("database-migration-invalid-manifest-staged")
        val database = directory.resolve("core.db")
        createPlaintextDatabase(database, "core")
        installStagedDatabase(stagePlaintextDatabase(database))
        writeUnreadableManifest(directory)

        val result = migrate(directory, listOf("core.db"))

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertEquals("core", readEncryptedValue(database))
        assertTrue(Files.list(directory).use { files -> files.noneMatch(::isMigrationArtifact) })
    }

    @Test
    fun migrateDatabases_unreadableManifestOverOrphanedSidecarBackup_erasesBackupAndKeepsDatabase() = runBlocking {
        val directory = Files.createTempDirectory("database-migration-invalid-manifest-orphan")
        val database = directory.resolve("core.db")
        createPlaintextDatabase(database, "core")
        installStagedDatabase(stagePlaintextDatabase(database))
        // The state an interrupted finishCommittedMigration leaves: no main backup, one stray sidecar.
        Files.delete(directory.resolve("core.db.plaintext-backup"))
        Files.writeString(directory.resolve("core.db-wal.plaintext-backup"), "stale-wal")
        writeUnreadableManifest(directory)

        val result = migrate(directory, listOf("core.db"))

        assertEquals(DatabaseMigrationResult(0, 1), result)
        assertEquals("core", readEncryptedValue(database))
        assertFalse(Files.exists(directory.resolve("core.db-wal")))
        assertTrue(Files.list(directory).use { files -> files.noneMatch(::isMigrationArtifact) })
    }

    @Test
    fun migrateDatabases_unreadableManifestBesideCommittedManifest_recoversBoth() = runBlocking {
        val directory = Files.createTempDirectory("database-migration-invalid-manifest-pair")
        val database = directory.resolve("core.db")
        createPlaintextDatabase(database, "core")
        val entry = stagePlaintextDatabase(database)
        installStagedDatabase(entry)
        writeManifest(directory, MigrationPhase.COMMITTED, listOf(entry))
        val unreadable = writeUnreadableManifest(directory)

        val result = migrate(directory, listOf("core.db"))

        assertEquals(DatabaseMigrationResult(0, 1), result)
        assertEquals("core", readEncryptedValue(database))
        assertFalse(Files.exists(unreadable))
        assertTrue(Files.list(directory).use { files -> files.noneMatch(::isMigrationArtifact) })
    }

    @Test
    fun migrateDatabases_orphanedMainBackupWithoutManifest_restoresDatabaseAndMigratesIt() = runBlocking {
        val directory = Files.createTempDirectory("database-migration-orphan-main")
        val database = directory.resolve("core.db")
        createPlaintextDatabase(database, "core")
        // An install interrupted between the two moves, whose manifest another wallet already discarded.
        stagePlaintextDatabase(database)
        Files.move(database, directory.resolve("core.db.plaintext-backup"))

        val result = migrate(directory, listOf("core.db"))

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertEquals("core", readEncryptedValue(database))
        assertTrue(Files.list(directory).use { files -> files.noneMatch(::isMigrationArtifact) })
    }

    @Test
    fun migrateDatabases_orphanedSidecarBackupWithoutManifest_erasesPlaintextResidue() = runBlocking {
        val directory = Files.createTempDirectory("database-migration-orphan-sidecar")
        val database = directory.resolve("core.db")
        createPlaintextDatabase(database, "core")
        installStagedDatabase(stagePlaintextDatabase(database))
        Files.delete(directory.resolve("core.db.plaintext-backup"))
        Files.writeString(directory.resolve("core.db-wal.plaintext-backup"), "stale-wal")

        val result = migrate(directory, listOf("core.db"))

        assertEquals(DatabaseMigrationResult(0, 1), result)
        assertEquals("core", readEncryptedValue(database))
        assertTrue(Files.list(directory).use { files -> files.noneMatch(::isMigrationArtifact) })
    }

    @Test
    fun migrateDatabases_truncatedMainBackupWithoutManifest_keepsEncryptedDatabase() = runBlocking {
        val directory = Files.createTempDirectory("database-migration-truncated-backup")
        val database = directory.resolve("core.db")
        createPlaintextDatabase(database, "core")
        installStagedDatabase(stagePlaintextDatabase(database))
        // eraseBackup truncates and fsyncs before unlinking: a kill in between leaves an empty backup.
        Files.write(directory.resolve("core.db.plaintext-backup"), ByteArray(0))

        val result = migrate(directory, listOf("core.db"))

        assertEquals(DatabaseMigrationResult(0, 1), result)
        assertEquals("core", readEncryptedValue(database))
        assertTrue(Files.list(directory).use { files -> files.noneMatch(::isMigrationArtifact) })
    }

    private fun createPlaintextDatabase(path: Path, value: String) {
        BundledSQLiteDriver().open(path.toString()).use { connection -> createSample(connection::execSQL, value) }
    }

    private fun createEncryptedDatabase(path: Path, value: String) {
        SqlCipherDriver(key).use { driver ->
            driver.open(path.toString()).use { connection -> createSample(connection::execSQL, value) }
        }
    }

    private fun createSample(execSql: (String) -> Unit, value: String) {
        execSql("CREATE TABLE sample(value TEXT NOT NULL)")
        execSql("INSERT INTO sample VALUES('$value')")
    }

    private fun readEncryptedValue(path: Path): String = SqlCipherDriver(key).use { driver ->
        driver.open(path.toString()).use { connection ->
            connection.prepare("SELECT value FROM sample").use { statement ->
                check(statement.step()) { "Test database contains no sample row" }
                statement.getText(0)
            }
        }
    }

    private suspend fun migrate(directory: Path, databaseNames: List<String>): DatabaseMigrationResult =
        DatabaseEncryption.migrateDatabases(
            dataDir = directory.toString(),
            databaseNames = databaseNames,
            migrationId = "wallet",
            databaseKey = key,
        )

    private fun stagePlaintextDatabase(database: Path): MigrationEntry {
        val staging = Path.of("$database.sqlcipher-migrating")
        exportPlaintextDatabase(database.toFile(), staging.toFile(), key)
        return MigrationEntry(database.toString(), staging.toString())
    }

    private fun installStagedDatabase(entry: MigrationEntry) {
        Files.move(
            entry.databaseFile.toPath(),
            Path.of("${entry.databasePath}.plaintext-backup"),
            StandardCopyOption.ATOMIC_MOVE,
        )
        Files.move(entry.stagingFile.toPath(), entry.databaseFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun writeUnreadableManifest(directory: Path): Path =
        Files.writeString(directory.resolve(".bitcoin-kit-sqlcipher-invalid.json"), "not-json")

    private fun writeManifest(directory: Path, phase: MigrationPhase, entries: List<MigrationEntry>) {
        val manifest = MigrationManifest(phase = phase, entries = entries)
        Files.writeString(
            directory.resolve(".bitcoin-kit-sqlcipher-test.json"),
            Json.encodeToString(manifest),
        )
    }

    private fun isMigrationArtifact(path: Path): Boolean = path.fileName.toString().let { name ->
        name.endsWith(".json") || name.endsWith(".sqlcipher-migrating") || name.endsWith(".plaintext-backup")
    }

    private companion object {
        val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
    }
}

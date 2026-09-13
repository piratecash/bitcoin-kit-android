package io.horizontalsystems.bitcoincore.storage

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest

data class DatabaseMigrationResult(
    val migratedDatabaseCount: Int,
    val alreadyEncryptedDatabaseCount: Int,
)

class DatabaseMigrationInProgressException(val manifestPath: String) : DatabaseEncryptionException(
    "Database migration must be recovered before opening a wallet: $manifestPath",
)

class DatabaseMigrationConflictException(message: String, cause: Throwable? = null) :
    DatabaseEncryptionException(message, cause)

class InsufficientDatabaseMigrationSpaceException(val requiredBytes: Long, val availableBytes: Long) :
    DatabaseEncryptionException("Database migration needs $requiredBytes bytes, but only $availableBytes bytes are available")

object DatabaseEncryption {
    fun supportedSyncModes(): List<SyncMode> = listOf(SyncMode.Api(), SyncMode.Full(), SyncMode.Blockchair())

    /**
     * Atomically replaces every existing plaintext database in one wallet group with SQLCipher files.
     * [databaseKey] is a raw 32-byte key. The operation is idempotent and must finish before a kit opens
     * any database from the group.
     */
    suspend fun migrateDatabases(
        dataDir: String,
        databaseNames: Collection<String>,
        migrationId: String,
        databaseKey: ByteArray,
    ): DatabaseMigrationResult = DatabaseMigrationCoordinator().migrate(
        dataDir = File(dataDir),
        databaseNames = databaseNames,
        migrationId = migrationId,
        databaseKey = databaseKey,
    )

    fun clearDatabases(
        dataDir: String,
        databaseNames: Collection<String>,
        migrationId: String,
    ) {
        clearDatabaseGroup(File(dataDir), databaseNames, migrationId)
    }
}

internal class DatabaseMigrationCoordinator(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun migrate(
        dataDir: File,
        databaseNames: Collection<String>,
        migrationId: String,
        databaseKey: ByteArray,
    ): DatabaseMigrationResult = withContext(ioDispatcher) {
        val key = validatedDatabaseKey(databaseKey)
        try {
            withDatabaseMigrationLock(dataDir) {
                recoverPendingMigrations(dataDir, databaseNames)
                migrateLocked(dataDir, databaseNames, migrationId, key)
            }
        } finally {
            key.fill(0)
        }
    }

    private suspend fun migrateLocked(
        dataDir: File,
        databaseNames: Collection<String>,
        migrationId: String,
        databaseKey: ByteArray,
    ): DatabaseMigrationResult {
        val databases = resolveDatabases(dataDir, databaseNames)
        val plaintext = databases.filter(::isPlaintextDatabase)
        val encrypted = databases - plaintext.toSet()
        encrypted.forEach { database ->
            try {
                verifyEncryptedDatabaseFile(database, databaseKey)
            } catch (error: RuntimeException) {
                throw DatabaseKeyMismatchException(database.path, error)
            }
        }
        if (plaintext.isNotEmpty() && encrypted.isNotEmpty()) {
            throw DatabaseMigrationConflictException("Database group contains both plaintext and encrypted files")
        }
        if (plaintext.isEmpty()) return DatabaseMigrationResult(0, encrypted.size)

        checkAvailableSpace(dataDir, plaintext)
        return executeMigration(dataDir, migrationId, plaintext, databaseKey)
    }

    private suspend fun executeMigration(
        dataDir: File,
        migrationId: String,
        plaintext: List<File>,
        databaseKey: ByteArray,
    ): DatabaseMigrationResult {
        val manifestFile = manifestFile(dataDir, migrationId)
        var manifest = MigrationManifest(phase = MigrationPhase.PREPARING, entries = plaintext.map(::migrationEntry))
        writeManifest(manifestFile, manifest)
        var committed = false
        try {
            stageDatabases(manifest.entries, databaseKey)
            manifest = manifest.copy(phase = MigrationPhase.STAGED)
            writeManifest(manifestFile, manifest)
            manifest.entries.forEach(::installStagedDatabase)
            forceDirectory(dataDir)
            manifest = manifest.copy(phase = MigrationPhase.COMMITTED)
            writeManifest(manifestFile, manifest)
            committed = true
            finishCommittedMigration(manifestFile, manifest)
            return DatabaseMigrationResult(manifest.entries.size, 0)
        } catch (error: Throwable) {
            recoverFailedMigration(manifestFile, manifest, committed)?.let(error::addSuppressed)
            throw error
        }
    }

    private suspend fun recoverFailedMigration(
        manifestFile: File,
        manifest: MigrationManifest,
        committed: Boolean,
    ): Throwable? = try {
        withContext(NonCancellable + ioDispatcher) {
            if (committed) finishCommittedMigration(manifestFile, manifest) else rollbackMigration(manifestFile, manifest)
        }
        null
    } catch (cleanupError: Throwable) {
        cleanupError
    }

    private fun stageDatabases(entries: List<MigrationEntry>, databaseKey: ByteArray) {
        entries.forEach { entry ->
            exportPlaintextDatabase(entry.databaseFile, entry.stagingFile, databaseKey)
            forceFile(entry.stagingFile)
        }
    }
}

internal fun verifyNoPendingDatabaseMigration(databasePath: String) {
    val directory = File(databasePath).absoluteFile.parentFile ?: return
    val manifest = directory.listFiles { file -> file.name.startsWith(MANIFEST_PREFIX) && file.name.endsWith(MANIFEST_SUFFIX) }
        ?.firstOrNull()
    if (manifest != null) throw DatabaseMigrationInProgressException(manifest.absolutePath)
}

internal inline fun <T> withDatabaseMigrationLock(dataDir: File, block: () -> T): T {
    require(dataDir.isDirectory || dataDir.mkdirs()) { "Database directory is unavailable: ${dataDir.path}" }
    val lockFile = File(dataDir, LOCK_FILE_NAME)
    return FileOutputStream(lockFile, true).channel.use { channel ->
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } ?: throw DatabaseMigrationConflictException("Another database migration is running")
        try {
            block()
        } finally {
            lock.release()
        }
    }
}

private fun resolveDatabases(dataDir: File, databaseNames: Collection<String>): List<File> {
    return resolveDatabasePaths(dataDir, databaseNames).filter(File::isFile)
}

private fun resolveDatabasePaths(dataDir: File, databaseNames: Collection<String>): List<File> {
    val canonicalDir = dataDir.canonicalFile
    return databaseNames.distinct().map { name ->
        require(name.isNotBlank() && name == File(name).name) { "Invalid database name: $name" }
        File(canonicalDir, name)
    }
}

private fun checkAvailableSpace(dataDir: File, databases: List<File>) {
    val databaseBytes = databases.sumOf { database -> sqliteDatabaseFamily(database).sumOf(File::length) }
    val required = databaseBytes + maxOf(MINIMUM_SPACE_MARGIN, databaseBytes / 5)
    val available = dataDir.usableSpace
    if (available < required) throw InsufficientDatabaseMigrationSpaceException(required, available)
}

private fun migrationEntry(database: File): MigrationEntry = MigrationEntry(
    databasePath = database.absolutePath,
    stagingPath = "${database.absolutePath}$STAGING_SUFFIX",
)

private fun installStagedDatabase(entry: MigrationEntry) {
    val database = entry.databaseFile
    sqliteDatabaseFamily(database).filter(File::exists).forEach { source -> atomicMove(source, backupFile(source)) }
    atomicMove(entry.stagingFile, database)
    forceFile(database)
}

private fun rollbackMigration(manifestFile: File, manifest: MigrationManifest) {
    rollbackEntries(manifest.entries)
    deleteIfExists(manifestFile)
    forceDirectory(manifestFile.parentFile)
}

private fun rollbackEntries(entries: List<MigrationEntry>) {
    entries.forEach { entry ->
        val database = entry.databaseFile
        val mainBackup = backupFile(database)
        // Only a readable plaintext main backup is a valid rollback source: an interrupted eraseBackup
        // leaves a truncated one, and restoring that would destroy the installed encrypted database.
        if (isPlaintextDatabase(mainBackup)) {
            sqliteDatabaseFamily(database).forEach(::deleteIfExists)
            sqliteDatabaseFamily(database).forEach { original ->
                val backup = backupFile(original)
                if (backup.exists()) atomicMove(backup, original)
            }
        } else {
            sqliteDatabaseFamily(database).forEach { original -> eraseBackup(backupFile(original)) }
        }
        sqliteDatabaseFamily(entry.stagingFile).forEach(::deleteIfExists)
    }
}

private fun finishCommittedMigration(manifestFile: File, manifest: MigrationManifest) {
    manifest.entries.forEach { entry ->
        sqliteDatabaseFamily(entry.databaseFile).forEach { original -> eraseBackup(backupFile(original)) }
        sqliteDatabaseFamily(entry.stagingFile).forEach(::deleteIfExists)
    }
    deleteIfExists(manifestFile)
    forceDirectory(manifestFile.parentFile)
}

private fun recoverPendingMigrations(dataDir: File, databaseNames: Collection<String>) {
    val ownEntries = resolveDatabasePaths(dataDir, databaseNames).map(::migrationEntry)
    val manifests = dataDir.listFiles { file ->
        file.name.startsWith(MANIFEST_PREFIX) && file.name.endsWith(MANIFEST_SUFFIX)
    } ?: return
    val unreadable = manifests.filterNot { file -> recoverFromManifest(dataDir, file) }
    // Another wallet may have discarded this group's unreadable manifest, so repair own residue unconditionally.
    rollbackEntries(ownEntries)
    unreadable.forEach(::deleteIfExists)
    forceDirectory(dataDir)
}

/** Returns false when the manifest could not be decoded, leaving it for the caller to discard. */
private fun recoverFromManifest(dataDir: File, manifestFile: File): Boolean {
    val manifest = try {
        readManifest(dataDir, manifestFile)
    } catch (_: DatabaseMigrationConflictException) {
        return false
    }
    when (manifest.phase) {
        MigrationPhase.COMMITTED -> finishCommittedMigration(manifestFile, manifest)
        MigrationPhase.CLEARING -> finishClearingDatabases(manifestFile, manifest)
        MigrationPhase.PREPARING,
        MigrationPhase.STAGED,
            -> rollbackMigration(manifestFile, manifest)
    }
    return true
}

private fun clearDatabaseGroup(dataDir: File, databaseNames: Collection<String>, migrationId: String) {
    withDatabaseMigrationLock(dataDir) {
        val manifestFile = manifestFile(dataDir, migrationId)
        val databasePaths = resolveDatabasePaths(dataDir, databaseNames)
        val pendingPaths = readPendingDatabasePaths(dataDir, manifestFile)
        val manifest = MigrationManifest(
            phase = MigrationPhase.CLEARING,
            entries = (databasePaths + pendingPaths).distinctBy { it.canonicalPath }.map(::migrationEntry),
        )
        writeManifest(manifestFile, manifest)
        finishClearingDatabases(manifestFile, manifest)
    }
}

private fun readPendingDatabasePaths(dataDir: File, manifestFile: File): List<File> {
    if (!manifestFile.isFile) return emptyList()
    return try {
        readManifest(dataDir, manifestFile).entries.map(MigrationEntry::databaseFile)
    } catch (_: DatabaseMigrationConflictException) {
        emptyList()
    }
}

private fun finishClearingDatabases(manifestFile: File, manifest: MigrationManifest) {
    manifest.entries.forEach { entry ->
        sqliteDatabaseFiles(entry.databaseFile).forEach(::deleteIfExists)
        sqliteDatabaseFamily(entry.databaseFile).forEach { original -> eraseBackup(backupFile(original)) }
        sqliteDatabaseFiles(entry.stagingFile).forEach(::deleteIfExists)
    }
    deleteIfExists(manifestFile)
    forceDirectory(manifestFile.parentFile)
}

private fun writeManifest(file: File, manifest: MigrationManifest) {
    val temporary = File(file.parentFile, "${file.name}.tmp")
    deleteIfExists(temporary)
    FileOutputStream(temporary).use { output ->
        output.write(JSON.encodeToString(manifest).encodeToByteArray())
        output.fd.sync()
    }
    atomicMove(temporary, file, replace = true)
    forceDirectory(file.parentFile)
}

private fun readManifest(dataDir: File, file: File): MigrationManifest {
    try {
        val manifest = JSON.decodeFromString<MigrationManifest>(file.readText())
        require(manifest.version == MANIFEST_VERSION) { "Unsupported database migration manifest version" }
        val canonicalDir = dataDir.canonicalFile
        manifest.entries.forEach { entry ->
            val database = entry.databaseFile.canonicalFile
            require(database.parentFile == canonicalDir) { "Migration manifest points outside the database directory" }
            require(entry.stagingFile.canonicalFile == File("${database.path}$STAGING_SUFFIX").canonicalFile) {
                "Migration manifest has an invalid staging path"
            }
        }
        return manifest
    } catch (error: Exception) {
        throw DatabaseMigrationConflictException("Database migration manifest is invalid: ${file.path}", error)
    }
}

private fun manifestFile(dataDir: File, migrationId: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(migrationId.encodeToByteArray())
    val id = buildString(16) {
        digest.take(8).forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_CHARS[value ushr 4])
            append(HEX_CHARS[value and 0x0f])
        }
    }
    return File(dataDir, "$MANIFEST_PREFIX$id$MANIFEST_SUFFIX")
}

private fun backupFile(file: File): File = File("${file.path}$BACKUP_SUFFIX")

private fun atomicMove(source: File, target: File, replace: Boolean = false) {
    try {
        platformAtomicMove(source, target, replace)
    } catch (error: IOException) {
        throw DatabaseMigrationConflictException("Atomic database rename is unavailable: ${error.message}")
    }
}

private fun deleteIfExists(file: File) {
    if (file.exists() && !file.deleteRecursively() && file.exists()) {
        throw IOException("Unable to delete ${file.path}")
    }
}

private fun forceFile(file: File) {
    RandomAccessFile(file, "rw").use { it.fd.sync() }
}

private fun forceDirectory(directory: File?) {
    directory?.let(::platformForceDirectory)
}

private fun eraseBackup(file: File) {
    if (!file.exists()) return
    RandomAccessFile(file, "rw").use { backup ->
        backup.setLength(0)
        backup.fd.sync()
    }
    deleteIfExists(file)
}

@Serializable
internal data class MigrationManifest(
    val version: Int = MANIFEST_VERSION,
    val phase: MigrationPhase,
    val entries: List<MigrationEntry>,
)

@Serializable
internal data class MigrationEntry(
    val databasePath: String,
    val stagingPath: String,
) {
    val databaseFile: File get() = File(databasePath)
    val stagingFile: File get() = File(stagingPath)
}

@Serializable
internal enum class MigrationPhase { PREPARING, STAGED, COMMITTED, CLEARING }

private val JSON = Json { ignoreUnknownKeys = false }
private const val MANIFEST_VERSION = 1
private const val MANIFEST_PREFIX = ".bitcoin-kit-sqlcipher-"
private const val MANIFEST_SUFFIX = ".json"
private const val LOCK_FILE_NAME = ".bitcoin-kit-sqlcipher.lock"
private const val STAGING_SUFFIX = ".sqlcipher-migrating"
private const val BACKUP_SUFFIX = ".plaintext-backup"
private const val MINIMUM_SPACE_MARGIN = 1024L * 1024L
private const val HEX_CHARS = "0123456789abcdef"

package io.horizontalsystems.sqlcipher.room

import androidx.room.RoomDatabase
import java.io.File

/** One instance per kit; [namespace] names its manifest and lock files and must never change once released. */
class SqlCipherDatabases(namespace: String) {
    internal val fileNames = MigrationFileNames(namespace)

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
    ): DatabaseMigrationResult = DatabaseMigrationCoordinator(fileNames).migrate(
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
        clearDatabaseGroup(File(dataDir), databaseNames, migrationId, fileNames)
    }

    fun deleteDatabaseFiles(dataDir: String, dbName: String) {
        deleteDatabaseFiles(dataDir, dbName, fileNames)
    }

    fun verifyPlaintextDatabaseAccess(path: String) {
        verifyPlaintextDatabaseAccess(path, fileNames)
    }

    /** Checks the file against [databaseKey] and configures [builder] to open it encrypted. */
    fun <T : RoomDatabase> encrypted(
        builder: RoomDatabase.Builder<T>,
        path: String,
        databaseKey: ByteArray,
    ): RoomDatabase.Builder<T> {
        verifyEncryptedDatabaseAccess(path, databaseKey, fileNames)
        return applySqlCipher(builder, databaseKey)
    }
}

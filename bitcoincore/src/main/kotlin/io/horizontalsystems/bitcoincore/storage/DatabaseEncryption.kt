package io.horizontalsystems.bitcoincore.storage

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.sqlcipher.room.SqlCipherDatabases
import java.io.File

typealias DatabaseMigrationResult = io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
typealias DatabaseEncryptionException = io.horizontalsystems.sqlcipher.room.DatabaseEncryptionException
typealias DatabaseMigrationRequiredException = io.horizontalsystems.sqlcipher.room.DatabaseMigrationRequiredException
typealias DatabaseKeyRequiredException = io.horizontalsystems.sqlcipher.room.DatabaseKeyRequiredException
typealias DatabaseKeyMismatchException = io.horizontalsystems.sqlcipher.room.DatabaseKeyMismatchException
typealias DatabaseMigrationInProgressException = io.horizontalsystems.sqlcipher.room.DatabaseMigrationInProgressException
typealias DatabaseMigrationConflictException = io.horizontalsystems.sqlcipher.room.DatabaseMigrationConflictException
typealias InsufficientDatabaseMigrationSpaceException =
    io.horizontalsystems.sqlcipher.room.InsufficientDatabaseMigrationSpaceException

@PublishedApi
internal val bitcoinKitDatabases = SqlCipherDatabases("bitcoin-kit")

object DatabaseEncryption {
    fun supportedSyncModes(): List<SyncMode> = listOf(SyncMode.Api(), SyncMode.Full(), SyncMode.Blockchair())

    suspend fun migrateDatabases(
        dataDir: String,
        databaseNames: Collection<String>,
        migrationId: String,
        databaseKey: ByteArray,
    ): DatabaseMigrationResult =
        bitcoinKitDatabases.migrateDatabases(dataDir, databaseNames, migrationId, databaseKey).also { result ->
            // An already-encrypted group is left untouched, so its cached instances stay valid.
            if (result.migratedDatabaseCount > 0) evictCachedInstances(dataDir, databaseNames)
        }

    fun clearDatabases(
        dataDir: String,
        databaseNames: Collection<String>,
        migrationId: String,
    ) {
        bitcoinKitDatabases.clearDatabases(dataDir, databaseNames, migrationId)
        evictCachedInstances(dataDir, databaseNames)
    }
}

fun deleteDatabaseFiles(dataDir: String, dbName: String) {
    bitcoinKitDatabases.deleteDatabaseFiles(dataDir, dbName)
    evictCachedInstances(dataDir, listOf(dbName))
}

private fun evictCachedInstances(dataDir: String, databaseNames: Collection<String>) {
    databaseNames.forEach { SharedRoomDatabases.evict(File(dataDir, it).path) }
}

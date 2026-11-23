package io.horizontalsystems.bitcoincore.storage

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.databasenaming.DatabaseNameResolver
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
internal fun bitcoinKitDatabases(nameResolver: DatabaseNameResolver): SqlCipherDatabases =
    SqlCipherDatabases(nameResolver.resolve("bitcoin-kit"))

object DatabaseEncryption {
    fun supportedSyncModes(): List<SyncMode> = listOf(SyncMode.Api(), SyncMode.Full(), SyncMode.Blockchair())

    /** [databaseNames] and [migrationId] are default names; [nameResolver] maps them to the on-disk ones. */
    suspend fun migrateDatabases(
        dataDir: String,
        databaseNames: Collection<String>,
        migrationId: String,
        databaseKey: ByteArray,
        nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
    ): DatabaseMigrationResult {
        val names = databaseNames.map(nameResolver::resolve)
        return bitcoinKitDatabases(nameResolver)
            .migrateDatabases(dataDir, names, nameResolver.resolve(migrationId), databaseKey)
            .also { result ->
                // An already-encrypted group is left untouched, so its cached instances stay valid.
                if (result.migratedDatabaseCount > 0) evictCachedInstances(dataDir, names)
            }
    }

    /** [databaseNames] and [migrationId] are default names; [nameResolver] maps them to the on-disk ones. */
    fun clearDatabases(
        dataDir: String,
        databaseNames: Collection<String>,
        migrationId: String,
        nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
    ) {
        val names = databaseNames.map(nameResolver::resolve)
        bitcoinKitDatabases(nameResolver).clearDatabases(dataDir, names, nameResolver.resolve(migrationId))
        evictCachedInstances(dataDir, names)
    }
}

fun deleteDatabaseFiles(
    dataDir: String,
    dbName: String,
    nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
) {
    val name = nameResolver.resolve(dbName)
    bitcoinKitDatabases(nameResolver).deleteDatabaseFiles(dataDir, name)
    evictCachedInstances(dataDir, listOf(name))
}

private fun evictCachedInstances(dataDir: String, databaseNames: Collection<String>) {
    databaseNames.forEach { SharedRoomDatabases.evict(File(dataDir, it).path) }
}

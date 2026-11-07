package io.horizontalsystems.bitcoincore.core

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.horizontalsystems.bitcoincore.storage.bitcoinKitDatabases
import io.horizontalsystems.databasenaming.DatabaseNameResolver
import kotlinx.coroutines.Dispatchers

// `allowMainThreadQueries` is ignored: there is no main-thread check off Android.
inline fun <reified T : RoomDatabase> databaseBuilder(
    path: String,
    allowMainThreadQueries: Boolean = false,
    nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
): RoomDatabase.Builder<T> =
    Room.databaseBuilder<T>(path).also { bitcoinKitDatabases(nameResolver).verifyPlaintextDatabaseAccess(path) }
        .setDriver(BundledSQLiteDriver())
        // A blocking DAO nested in a transaction must reach Room's `useConnection` undispatched, before
        // its first suspension, so Room recovers the transaction's connection from its thread local.
        .setQueryCoroutineContext(Dispatchers.Unconfined)

inline fun <reified T : RoomDatabase> databaseBuilder(
    path: String,
    databaseKey: ByteArray?,
    allowMainThreadQueries: Boolean = false,
    nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
): RoomDatabase.Builder<T> {
    if (databaseKey == null) return databaseBuilder(path, allowMainThreadQueries, nameResolver)
    return bitcoinKitDatabases(nameResolver).encrypted(Room.databaseBuilder<T>(path), path, databaseKey)
        .setQueryCoroutineContext(Dispatchers.Unconfined)
}

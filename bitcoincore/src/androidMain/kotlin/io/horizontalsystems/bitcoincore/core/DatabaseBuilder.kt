package io.horizontalsystems.bitcoincore.core

import androidx.room.Room
import androidx.room.RoomDatabase
import io.horizontalsystems.bitcoincore.storage.bitcoinKitDatabases
import io.horizontalsystems.databasenaming.DatabaseNameResolver

inline fun <reified T : RoomDatabase> databaseBuilder(
    path: String,
    allowMainThreadQueries: Boolean = false,
    nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
): RoomDatabase.Builder<T> =
    Room.databaseBuilder(appContext, T::class.java, path).also { bitcoinKitDatabases(nameResolver).verifyPlaintextDatabaseAccess(path) }
        .apply { if (allowMainThreadQueries) allowMainThreadQueries() }

inline fun <reified T : RoomDatabase> databaseBuilder(
    path: String,
    databaseKey: ByteArray?,
    allowMainThreadQueries: Boolean = false,
    nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
): RoomDatabase.Builder<T> {
    if (databaseKey == null) return databaseBuilder(path, allowMainThreadQueries, nameResolver)
    return bitcoinKitDatabases(nameResolver).encrypted(Room.databaseBuilder(appContext, T::class.java, path), path, databaseKey)
        .apply { if (allowMainThreadQueries) allowMainThreadQueries() }
}

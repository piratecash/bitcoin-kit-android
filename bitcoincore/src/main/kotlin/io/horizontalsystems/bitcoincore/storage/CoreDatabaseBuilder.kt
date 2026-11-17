package io.horizontalsystems.bitcoincore.storage

import androidx.room.RoomDatabase
import io.horizontalsystems.bitcoincore.core.databaseBuilder
import io.horizontalsystems.databasenaming.DatabaseNameResolver

internal fun coreDatabaseBuilder(path: String): RoomDatabase.Builder<CoreDatabase> =
    databaseBuilder<CoreDatabase>(path, allowMainThreadQueries = true)

internal fun coreDatabaseBuilder(
    path: String,
    databaseKey: ByteArray?,
    nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
): RoomDatabase.Builder<CoreDatabase> =
    databaseBuilder<CoreDatabase>(path, databaseKey, allowMainThreadQueries = true, nameResolver = nameResolver)

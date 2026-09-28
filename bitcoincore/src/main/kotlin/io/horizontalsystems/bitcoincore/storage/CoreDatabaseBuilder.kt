package io.horizontalsystems.bitcoincore.storage

import androidx.room.RoomDatabase
import io.horizontalsystems.bitcoincore.core.databaseBuilder

internal fun coreDatabaseBuilder(path: String): RoomDatabase.Builder<CoreDatabase> =
    databaseBuilder<CoreDatabase>(path, allowMainThreadQueries = true)

internal fun coreDatabaseBuilder(path: String, databaseKey: ByteArray?): RoomDatabase.Builder<CoreDatabase> =
    databaseBuilder<CoreDatabase>(path, databaseKey, allowMainThreadQueries = true)

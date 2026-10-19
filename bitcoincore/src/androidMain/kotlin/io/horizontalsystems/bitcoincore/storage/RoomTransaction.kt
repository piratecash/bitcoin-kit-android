package io.horizontalsystems.bitcoincore.storage

import androidx.room.RoomDatabase

fun RoomDatabase.inTransaction(body: () -> Unit) = runInTransaction(body)

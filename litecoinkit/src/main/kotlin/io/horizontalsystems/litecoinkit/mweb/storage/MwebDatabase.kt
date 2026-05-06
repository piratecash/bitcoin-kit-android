package io.horizontalsystems.litecoinkit.mweb.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        MwebAddressEntity::class,
        MwebUtxoEntity::class,
        MwebStateEntity::class,
        MwebPendingTransactionEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(MwebTypeConverters::class)
abstract class MwebDatabase : RoomDatabase() {
    abstract val addressDao: MwebAddressDao
    abstract val utxoDao: MwebUtxoDao
    abstract val stateDao: MwebStateDao
    abstract val pendingTransactionDao: MwebPendingTransactionDao

    companion object {
        fun getInstance(context: Context, dbName: String): MwebDatabase {
            return Room.databaseBuilder(context, MwebDatabase::class.java, dbName)
                .build()
        }
    }
}

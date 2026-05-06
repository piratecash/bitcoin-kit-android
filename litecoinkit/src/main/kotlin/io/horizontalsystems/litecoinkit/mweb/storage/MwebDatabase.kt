package io.horizontalsystems.litecoinkit.mweb.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MwebAddressEntity::class,
        MwebUtxoEntity::class,
        MwebStateEntity::class,
        MwebPendingTransactionEntity::class,
        MwebOutgoingTransactionEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(MwebTypeConverters::class)
abstract class MwebDatabase : RoomDatabase() {
    abstract val addressDao: MwebAddressDao
    abstract val utxoDao: MwebUtxoDao
    abstract val stateDao: MwebStateDao
    abstract val pendingTransactionDao: MwebPendingTransactionDao
    abstract val outgoingTransactionDao: MwebOutgoingTransactionDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `MwebOutgoingTransaction` (
                        `uid` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `fee` INTEGER NOT NULL,
                        `destinationAddress` TEXT NOT NULL,
                        `canonicalTransactionHash` TEXT,
                        `createdOutputIds` TEXT NOT NULL,
                        `spentOutputIds` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`uid`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context, dbName: String): MwebDatabase {
            return Room.databaseBuilder(context, MwebDatabase::class.java, dbName)
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}

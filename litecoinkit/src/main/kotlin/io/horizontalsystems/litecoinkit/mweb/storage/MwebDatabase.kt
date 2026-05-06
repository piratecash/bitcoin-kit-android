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
    version = 3,
    exportSchema = true,
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
                        `type` TEXT NOT NULL DEFAULT 'Outgoing',
                        `kind` TEXT NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `fee` INTEGER,
                        `destinationAddress` TEXT,
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `MwebOutgoingTransactionV3` (
                        `uid` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `fee` INTEGER,
                        `destinationAddress` TEXT,
                        `canonicalTransactionHash` TEXT,
                        `createdOutputIds` TEXT NOT NULL,
                        `spentOutputIds` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`uid`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `MwebOutgoingTransactionV3` (
                        `uid`,
                        `type`,
                        `kind`,
                        `amount`,
                        `fee`,
                        `destinationAddress`,
                        `canonicalTransactionHash`,
                        `createdOutputIds`,
                        `spentOutputIds`,
                        `timestamp`
                    )
                    SELECT
                        `uid`,
                        'Outgoing',
                        `kind`,
                        `amount`,
                        `fee`,
                        NULLIF(`destinationAddress`, ''),
                        `canonicalTransactionHash`,
                        `createdOutputIds`,
                        `spentOutputIds`,
                        `timestamp`
                    FROM `MwebOutgoingTransaction`
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE `MwebOutgoingTransaction`")
                database.execSQL("ALTER TABLE `MwebOutgoingTransactionV3` RENAME TO `MwebOutgoingTransaction`")
            }
        }

        fun getInstance(context: Context, dbName: String): MwebDatabase {
            return Room.databaseBuilder(context, MwebDatabase::class.java, dbName)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }
    }
}

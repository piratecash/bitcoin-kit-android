package io.horizontalsystems.litecoinkit.mweb.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.horizontalsystems.bitcoincore.core.databaseBuilder
import java.io.File

@Database(
    entities = [
        MwebAddressEntity::class,
        MwebUtxoEntity::class,
        MwebStateEntity::class,
        MwebDeliveryCursorEntity::class,
        MwebPendingTransactionEntity::class,
        MwebOutgoingTransactionEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(MwebTypeConverters::class)
abstract class MwebDatabase : RoomDatabase() {
    abstract val addressDao: MwebAddressDao
    abstract val utxoDao: MwebUtxoDao
    abstract val stateDao: MwebStateDao
    abstract val deliveryCursorDao: MwebDeliveryCursorDao
    abstract val pendingTransactionDao: MwebPendingTransactionDao
    abstract val outgoingTransactionDao: MwebOutgoingTransactionDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
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
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
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
                connection.execSQL(
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
                connection.execSQL("DROP TABLE `MwebOutgoingTransaction`")
                connection.execSQL("ALTER TABLE `MwebOutgoingTransactionV3` RENAME TO `MwebOutgoingTransaction`")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `MwebOutgoingTransaction` ADD COLUMN `confirmedHeight` INTEGER")
                connection.execSQL("ALTER TABLE `MwebOutgoingTransaction` ADD COLUMN `confirmedTimestamp` INTEGER")
                connection.execSQL(
                    """
                    UPDATE `MwebOutgoingTransaction`
                    SET
                        `confirmedHeight` = (SELECT `mwebUtxosHeight` FROM `MwebState` WHERE `id` = 0),
                        `confirmedTimestamp` = `timestamp`
                    WHERE
                        `type` = 'Outgoing' AND
                        `createdOutputIds` = '[]' AND
                        `spentOutputIds` != '[]' AND
                        COALESCE((SELECT `mwebUtxosHeight` FROM `MwebState` WHERE `id` = 0), 0) > 0
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `MwebDeliveryCursor` (
                        `id` INTEGER NOT NULL,
                        `utxoDeliveryHeight` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    """
                    INSERT INTO `MwebDeliveryCursor` (`id`, `utxoDeliveryHeight`)
                    SELECT 0, CASE
                        WHEN `mwebUtxosHeight` > $MIGRATION_4_5_REPLAY_BLOCKS THEN `mwebUtxosHeight` - $MIGRATION_4_5_REPLAY_BLOCKS
                        ELSE 0
                    END FROM `MwebState` WHERE `id` = 0
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        private const val MIGRATION_4_5_REPLAY_BLOCKS = 2_880

        fun getInstance(dataDir: String, dbName: String): MwebDatabase {
            return buildDatabase(dataDir, dbName)
        }

        fun getInstance(dataDir: String, dbName: String, databaseKey: ByteArray?): MwebDatabase {
            return buildDatabase(dataDir, dbName, databaseKey)
        }

        private fun buildDatabase(dataDir: String, dbName: String, databaseKey: ByteArray? = null): MwebDatabase {
            val path = File(dataDir, dbName).path
            return databaseBuilder<MwebDatabase>(path, databaseKey)
                .addMigrations(*MIGRATIONS)
                .build()
        }
    }
}

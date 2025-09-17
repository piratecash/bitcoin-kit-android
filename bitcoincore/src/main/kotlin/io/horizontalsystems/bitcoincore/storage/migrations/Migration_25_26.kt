package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_25_26 : Migration(25, 26) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `OrphanBlock` (
                `block_version` INTEGER NOT NULL,
                `previousBlockHash` BLOB NOT NULL,
                `merkleRoot` BLOB NOT NULL,
                `block_timestamp` INTEGER NOT NULL,
                `bits` INTEGER NOT NULL,
                `nonce` INTEGER NOT NULL,
                `hasTransactions` INTEGER NOT NULL,
                `headerHash` BLOB NOT NULL PRIMARY KEY,
                `height` INTEGER NOT NULL,
                `stale` INTEGER NOT NULL,
                `partial` INTEGER NOT NULL
            )
        """.trimIndent()
        )

        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `Block_new` (
                `block_version` INTEGER NOT NULL,
                `previousBlockHash` BLOB NOT NULL,
                `merkleRoot` BLOB NOT NULL,
                `block_timestamp` INTEGER NOT NULL,
                `bits` INTEGER NOT NULL,
                `nonce` INTEGER NOT NULL,
                `hasTransactions` INTEGER NOT NULL,
                `headerHash` BLOB NOT NULL PRIMARY KEY,
                `height` INTEGER NOT NULL,
                `stale` INTEGER NOT NULL,
                `partial` INTEGER NOT NULL
            )
        """.trimIndent()
        )

        database.execSQL(
            """
            INSERT INTO Block_new (
                block_version, previousBlockHash, merkleRoot,
                block_timestamp, bits, nonce, hasTransactions,
                headerHash, height, stale, partial
            )
            SELECT 
                block_version, previousBlockHash, merkleRoot,
                block_timestamp, bits, nonce, hasTransactions,
                headerHash, height, stale, partial
            FROM Block
            WHERE orphan = 0
        """.trimIndent()
        )

        database.execSQL("DROP TABLE Block")

        database.execSQL("ALTER TABLE Block_new RENAME TO Block")

        database.execSQL("CREATE INDEX IF NOT EXISTS `index_Block_height` ON `Block` (`height`)")
    }
}

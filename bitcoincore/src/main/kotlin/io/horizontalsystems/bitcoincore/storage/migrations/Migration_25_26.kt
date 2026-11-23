package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration_25_26 : Migration(25, 26) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
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

        connection.execSQL(
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

        connection.execSQL(
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

        connection.execSQL("DROP TABLE Block")

        connection.execSQL("ALTER TABLE Block_new RENAME TO Block")

        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_Block_height` ON `Block` (`height`)")
    }
}

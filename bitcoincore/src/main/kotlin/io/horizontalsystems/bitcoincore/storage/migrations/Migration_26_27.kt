package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration_26_27 : Migration(26, 27) {
    override fun migrate(connection: SQLiteConnection) {
        // Create new OrphanBlock table without height, stale, partial columns
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `OrphanBlock_new` (
                `block_version` INTEGER NOT NULL,
                `previousBlockHash` BLOB NOT NULL,
                `merkleRoot` BLOB NOT NULL,
                `block_timestamp` INTEGER NOT NULL,
                `bits` INTEGER NOT NULL,
                `nonce` INTEGER NOT NULL,
                `hasTransactions` INTEGER NOT NULL,
                `headerHash` BLOB NOT NULL PRIMARY KEY
            )
        """.trimIndent()
        )

        // Copy data from old table to new table (excluding dropped columns)
        connection.execSQL(
            """
            INSERT INTO OrphanBlock_new (
                block_version, previousBlockHash, merkleRoot,
                block_timestamp, bits, nonce, hasTransactions,
                headerHash
            )
            SELECT 
                block_version, previousBlockHash, merkleRoot,
                block_timestamp, bits, nonce, hasTransactions,
                headerHash
            FROM OrphanBlock
        """.trimIndent()
        )

        // Drop old table and rename new table
        connection.execSQL("DROP TABLE OrphanBlock")
        connection.execSQL("ALTER TABLE OrphanBlock_new RENAME TO OrphanBlock")
    }
}

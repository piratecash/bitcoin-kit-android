package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration_28_29 : Migration(28, 29) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS orphanblock")

        connection.execSQL("""
            CREATE TABLE orphanblock (
                block_version INTEGER NOT NULL,
                previousBlockHash BLOB NOT NULL,
                merkleRoot BLOB NOT NULL,
                block_timestamp INTEGER NOT NULL,
                bits INTEGER NOT NULL,
                nonce INTEGER NOT NULL,
                hasTransactions INTEGER NOT NULL,
                headerHash BLOB NOT NULL PRIMARY KEY,
                merkleBlock TEXT
            )
        """.trimIndent())
    }
}

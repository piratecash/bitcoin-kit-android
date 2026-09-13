package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration_30_31 : Migration(30, 31) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_Transaction_blockHash_status_isMine`
            ON `Transaction` (`blockHash`, `status`, `isMine`)
            """.trimIndent()
        )
    }
}

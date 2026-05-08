package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_30_31 : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_Transaction_blockHash_status_isMine`
            ON `Transaction` (`blockHash`, `status`, `isMine`)
            """.trimIndent()
        )
    }
}

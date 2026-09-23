package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration_10_11 : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `TransactionOutput` ADD COLUMN `failedToSpend` INTEGER DEFAULT 0 NOT NULL")
    }
}

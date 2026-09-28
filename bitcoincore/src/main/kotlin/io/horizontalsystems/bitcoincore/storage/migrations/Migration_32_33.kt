package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration_32_33 : Migration(32, 33) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `SentTransaction` ADD COLUMN `rawTransactionHex` TEXT")
        connection.execSQL("ALTER TABLE `SentTransaction` ADD COLUMN `external` INTEGER NOT NULL DEFAULT 0")
    }
}

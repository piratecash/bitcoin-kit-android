package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration_19_20 : Migration(19, 20) {

    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `Block` ADD COLUMN orphan INTEGER NOT NULL DEFAULT 0")
    }

}

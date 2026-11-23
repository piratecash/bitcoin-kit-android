package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration_20_21 : Migration(20, 21) {

    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DELETE FROM `Block` WHERE `height` = 1")
    }

}

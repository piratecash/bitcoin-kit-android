package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration_11_12 : Migration(11, 12) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DELETE FROM `PeerAddress`")
    }
}

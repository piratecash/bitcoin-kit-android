package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_20_21 : Migration(20, 21) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DELETE FROM `Block` WHERE `height` = 1")
    }

}

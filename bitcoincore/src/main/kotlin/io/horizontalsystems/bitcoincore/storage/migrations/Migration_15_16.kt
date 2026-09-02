package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration_15_16 : Migration(15, 16) {

    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("UPDATE `TransactionOutput` SET `lockingScriptPayload` = SUBSTR(`lockingScriptPayload`, 3) WHERE scriptType = 4 and publicKeyPath is not null")
    }

}

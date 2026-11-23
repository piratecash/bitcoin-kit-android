package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration_17_18 : Migration(17, 18) {

    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `TransactionInput` RENAME TO `TmpTransactionInput`")

        connection.execSQL("CREATE TABLE IF NOT EXISTS `TransactionInput` (`previousOutputTxHash` BLOB NOT NULL, `previousOutputIndex` INTEGER NOT NULL, `sigScript` BLOB NOT NULL, `sequence` INTEGER NOT NULL, `transactionHash` BLOB NOT NULL, `lockingScriptPayload` BLOB, `address` TEXT, `witness` TEXT NOT NULL, PRIMARY KEY(`previousOutputTxHash`, `previousOutputIndex`, `sequence`), FOREIGN KEY(`transactionHash`) REFERENCES `Transaction`(`hash`) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)")
        connection.execSQL("INSERT OR REPLACE INTO `TransactionInput` (`transactionHash`,`lockingScriptPayload`,`address`,`witness`,`previousOutputTxHash`,`previousOutputIndex`,`sigScript`,`sequence`) SELECT `transactionHash`,`lockingScriptPayload`,`address`,`witness`,`previousOutputTxHash`,`previousOutputIndex`,`sigScript`,`sequence` FROM `TmpTransactionInput`")

        connection.execSQL("DROP TABLE IF EXISTS `TmpTransactionInput`")
    }

}

package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration_14_15 : Migration(14, 15) {

    override fun migrate(connection: SQLiteConnection) {
        addColumnToPublicKey(connection)
        renameColumnsInTransactionInput(connection)
        renameColumnsInTransactionOutput(connection)
    }

    private fun addColumnToPublicKey(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE PublicKey ADD COLUMN convertedForP2TR BLOB DEFAULT '' NOT NULL")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_PublicKey_convertedForP2TR` ON `PublicKey` (`convertedForP2TR`)")
    }

    private fun renameColumnsInTransactionInput(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `TransactionInput` RENAME TO `TmpTransactionInput`")

        connection.execSQL("CREATE TABLE IF NOT EXISTS `TransactionInput` (`transactionHash` BLOB NOT NULL, `lockingScriptPayload` BLOB, `address` TEXT, `witness` TEXT NOT NULL, `previousOutputTxHash` BLOB NOT NULL, `previousOutputIndex` INTEGER NOT NULL, `sigScript` BLOB NOT NULL, `sequence` INTEGER NOT NULL, PRIMARY KEY(`previousOutputTxHash`, `previousOutputIndex`), FOREIGN KEY(`transactionHash`) REFERENCES `Transaction`(`hash`) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)")
        connection.execSQL("INSERT OR REPLACE INTO `TransactionInput` (`transactionHash`,`lockingScriptPayload`,`address`,`witness`,`previousOutputTxHash`,`previousOutputIndex`,`sigScript`,`sequence`) SELECT `transactionHash`,`keyHash`,`address`,`witness`,`previousOutputTxHash`,`previousOutputIndex`,`sigScript`,`sequence` FROM `TmpTransactionInput`")

        connection.execSQL("DROP TABLE IF EXISTS `TmpTransactionInput`")
    }

    private fun renameColumnsInTransactionOutput(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `TransactionOutput` RENAME TO `TmpTransactionOutput`")

        connection.execSQL("CREATE TABLE IF NOT EXISTS `TransactionOutput` (`value` INTEGER NOT NULL, `lockingScript` BLOB NOT NULL, `redeemScript` BLOB, `index` INTEGER NOT NULL, `transactionHash` BLOB NOT NULL, `publicKeyPath` TEXT, `changeOutput` INTEGER NOT NULL, `scriptType` INTEGER NOT NULL, `lockingScriptPayload` BLOB, `address` TEXT, `failedToSpend` INTEGER NOT NULL, `pluginId` INTEGER, `pluginData` TEXT, PRIMARY KEY(`transactionHash`, `index`), FOREIGN KEY(`publicKeyPath`) REFERENCES `PublicKey`(`path`) ON UPDATE SET NULL ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`transactionHash`) REFERENCES `Transaction`(`hash`) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)")
        connection.execSQL("INSERT OR REPLACE INTO `TransactionOutput` (`value`,`lockingScript`,`redeemScript`,`index`,`transactionHash`,`publicKeyPath`,`changeOutput`,`scriptType`,`lockingScriptPayload`,`address`,`failedToSpend`,`pluginId`,`pluginData`) SELECT `value`,`lockingScript`,`redeemScript`,`index`,`transactionHash`,`publicKeyPath`,`changeOutput`,`scriptType`,`keyHash`,`address`,`failedToSpend`,`pluginId`,`pluginData` FROM `TmpTransactionOutput`")

        connection.execSQL("DROP TABLE IF EXISTS `TmpTransactionOutput`")
    }

}

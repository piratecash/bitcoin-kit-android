package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_32_33 : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `SentTransaction` ADD COLUMN `rawTransactionHex` TEXT")
        db.execSQL("ALTER TABLE `SentTransaction` ADD COLUMN `external` INTEGER NOT NULL DEFAULT 0")
    }
}

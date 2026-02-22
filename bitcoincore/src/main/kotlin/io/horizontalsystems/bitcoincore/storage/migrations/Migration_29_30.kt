package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Fixes incorrectly marked coinbase transactions.
 *
 * Coinbase transactions (masternode payouts, mining rewards) were incorrectly detected
 * as "conflicting" with each other because they all share the same null-hash input
 * (previousOutputTxHash = 32 bytes of zeros).
 *
 * This migration clears the conflictingTxHash for all coinbase-based transactions,
 * restoring their correct balance and removing warning icons.
 */
object Migration_29_30 : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Clear conflictingTxHash for coinbase transactions that were incorrectly
        // marked as conflicting with each other (all coinbase txs share null hash input)
        db.execSQL("""
            UPDATE `Transaction`
            SET conflictingTxHash = NULL
            WHERE hash IN (
                SELECT DISTINCT transactionHash
                FROM TransactionInput
                WHERE previousOutputTxHash = X'0000000000000000000000000000000000000000000000000000000000000000'
            )
        """.trimIndent())
    }
}

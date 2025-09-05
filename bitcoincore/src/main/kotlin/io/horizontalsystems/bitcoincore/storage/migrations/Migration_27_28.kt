package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * This migration clears all blocks, orphan blocks, and block hashes from the database
 * to resolve synchronization hangs caused by blocks with incorrect heights.
 *
 * The migration will:
 * 1. Delete all blocks from the Block table
 * 2. Delete all orphan blocks from the OrphanBlock table
 * 3. Delete all block hashes from the BlockHash table
 * 4. Reset the blockchain state to force re-synchronization
 * 5. Clean up related transaction data that depends on blocks
 */
object Migration_27_28 : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Clear all blocks (both stale and non-stale)
        db.execSQL("DELETE FROM Block")

        // Clear all orphan blocks
        db.execSQL("DELETE FROM OrphanBlock")

        // Clear all block hashes
        db.execSQL("DELETE FROM BlockHash")

        // Clear block hash public keys
        db.execSQL("DELETE FROM BlockHashPublicKey")

        // Reset blockchain state to force re-synchronization
        db.execSQL("UPDATE BlockchainState SET initialRestored = 0")

        // Clean up transactions that are associated with blocks
        db.execSQL("DELETE FROM `Transaction`")

        // Clean up transaction inputs for deleted transactions
        db.execSQL("DELETE FROM `TransactionInput`")

        // Clean up transaction outputs for deleted transactions
        db.execSQL("DELETE FROM `TransactionOutput`")

        // Clean up transaction metadata for deleted transactions
        db.execSQL("DELETE FROM `TransactionMetadata`")

        // Clean up invalid transactions that might be associated with blocks
        db.execSQL("DELETE FROM `InvalidTransaction`")
    }
}

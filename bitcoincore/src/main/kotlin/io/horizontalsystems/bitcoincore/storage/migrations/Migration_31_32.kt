package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

// Drops the cached peer addresses once. Installs affected by the eCash/BCH seed mix accumulated
// BCH peer addresses that connect successfully and are never evicted, so the new DNS seeds were
// never queried. Clearing the table forces a fresh lookup. Harmless for other chains: they simply
// rediscover peers from their own seeds. Only peer addresses are removed, not blocks or transactions.
object Migration_31_32 : Migration(31, 32) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DELETE FROM `PeerAddress`")
    }
}

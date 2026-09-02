package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.room.util.getColumnIndex
import androidx.sqlite.SQLiteConnection

object Migration_18_19 : Migration(18, 19) {

    // Errors propagate on purpose: migrate() runs inside Room's own transaction, so that rolls
    // back every step, while a raw ROLLBACK TO SAVEPOINT here would desync SQLiteSession.
    override fun migrate(connection: SQLiteConnection) {
        migratePublicKeyPath(connection)
        migrateTransactionOutput(connection)
        migrateBlockHashPublicKey(connection)
    }

    // The "tmp-" prefix avoids a PRIMARY KEY collision when two paths swap (0/x <-> 1/x);
    // rows are read into a list first so the read is not disturbed by the UPDATEs.
    private fun migratePublicKeyPath(connection: SQLiteConnection) {
        val originalPaths = readPublicKeyPaths(connection) ?: return
        for (path in originalPaths) {
            updatePublicKeyPath(connection, newPath = "tmp-${fixedPath(path)}", oldPath = path)
        }

        val tmpPaths = readPublicKeyPaths(connection) ?: return
        for (path in tmpPaths) {
            updatePublicKeyPath(connection, newPath = path.removePrefix("tmp-"), oldPath = path)
        }
    }

    private fun readPublicKeyPaths(connection: SQLiteConnection): List<String>? {
        connection.prepare("SELECT * FROM `PublicKey`").use { st ->
            val pathIndex = getColumnIndex(st, "path")
            if (pathIndex < 0) return null

            val paths = mutableListOf<String>()
            while (st.step()) {
                paths.add(st.getText(pathIndex))
            }
            return paths
        }
    }

    private fun updatePublicKeyPath(connection: SQLiteConnection, newPath: String, oldPath: String) {
        connection.prepare("UPDATE OR IGNORE `PublicKey` SET path = ? WHERE path = ?").use { st ->
            st.bindText(1, newPath)
            st.bindText(2, oldPath)
            st.step()
        }
    }

    private fun migrateTransactionOutput(connection: SQLiteConnection) {
        data class Row(val transactionHash: ByteArray, val index: String, val path: String)

        val rows = mutableListOf<Row>()
        connection.prepare("SELECT * FROM `TransactionOutput` WHERE publicKeyPath IS NOT NULL").use { st ->
            val publicKeyPathIndex = getColumnIndex(st, "publicKeyPath")
            val transactionHashIndex = getColumnIndex(st, "transactionHash")
            val indexIndex = getColumnIndex(st, "index")
            if (publicKeyPathIndex < 0 || transactionHashIndex < 0 || indexIndex < 0) return

            while (st.step()) {
                // `index` is bound as TEXT and matched by SQLite type affinity, as before.
                rows.add(Row(st.getBlob(transactionHashIndex), st.getText(indexIndex), st.getText(publicKeyPathIndex)))
            }
        }

        for (row in rows) {
            connection.prepare(
                "UPDATE OR IGNORE `TransactionOutput` SET publicKeyPath = ? WHERE transactionHash = ? AND `index` = ?"
            ).use { st ->
                st.bindText(1, fixedPath(row.path))
                st.bindBlob(2, row.transactionHash)
                st.bindText(3, row.index)
                st.step()
            }
        }
    }

    private fun migrateBlockHashPublicKey(connection: SQLiteConnection) {
        val rows = mutableListOf<Pair<ByteArray, String>>()
        connection.prepare("SELECT * FROM `BlockHashPublicKey` WHERE publicKeyPath IS NOT NULL").use { st ->
            val publicKeyPathIndex = getColumnIndex(st, "publicKeyPath")
            val blockHashIndex = getColumnIndex(st, "blockHash")
            if (publicKeyPathIndex < 0 || blockHashIndex < 0) return

            while (st.step()) {
                rows.add(st.getBlob(blockHashIndex) to st.getText(publicKeyPathIndex))
            }
        }

        for ((blockHash, path) in rows) {
            connection.prepare(
                "UPDATE OR IGNORE `BlockHashPublicKey` SET publicKeyPath = ? WHERE blockHash = ? AND publicKeyPath = ?"
            ).use { st ->
                st.bindText(1, fixedPath(path))
                st.bindBlob(2, blockHash)
                st.bindText(3, path)
                st.step()
            }
        }
    }

    private fun fixedPath(path: String): String {
        val parts = path.split("/").map { it.toInt() }
        if (parts.size != 3) return path
        val account = parts[0]
        val change = if (parts[1] == 0) 1 else 0
        val index = parts[2]
        return "$account/$change/$index"
    }
}

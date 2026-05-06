package io.horizontalsystems.litecoinkit.mweb

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.horizontalsystems.litecoinkit.LitecoinKit
import java.io.File

internal object MwebFiles {
    fun databaseName(networkType: LitecoinKit.NetworkType, walletId: String): String {
        return "Litecoin-MWEB-${networkType.name}-$walletId"
    }

    fun daemonDataDir(context: Context, networkType: LitecoinKit.NetworkType, walletId: String): File {
        return File(context.noBackupFilesDir, databaseName(networkType, walletId))
    }

    fun clear(context: Context, networkType: LitecoinKit.NetworkType, walletId: String) {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(databaseName(networkType, walletId)))
        daemonDataDir(context, networkType, walletId).deleteRecursively()
    }
}

package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.bitcoincore.storage.deleteDatabaseFiles
import io.horizontalsystems.litecoinkit.LitecoinKit
import java.io.File

internal object MwebFiles {
    fun databaseName(networkType: LitecoinKit.NetworkType, walletId: String): String {
        return "Litecoin-MWEB-${networkType.name}-$walletId"
    }

    fun daemonDataDir(mwebDataDir: String, networkType: LitecoinKit.NetworkType, walletId: String): File {
        return File(mwebDataDir, databaseName(networkType, walletId))
    }

    fun publicSendDaemonDataDir(mwebDataDir: String, networkType: LitecoinKit.NetworkType, walletId: String): File {
        return File(mwebDataDir, "${databaseName(networkType, walletId)}-PublicSend")
    }

    fun clear(dataDir: String, mwebDataDir: String, networkType: LitecoinKit.NetworkType, walletId: String) {
        MwebPublicPegInSender.checkCanClear(walletId, networkType)
        deleteDatabaseFiles(dataDir, databaseName(networkType, walletId))
        clearDaemonData(mwebDataDir, networkType, walletId)
    }

    fun clearDaemonData(mwebDataDir: String, networkType: LitecoinKit.NetworkType, walletId: String) {
        daemonDataDir(mwebDataDir, networkType, walletId).deleteRecursively()
        publicSendDaemonDataDir(mwebDataDir, networkType, walletId).deleteRecursively()
    }
}

package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.bitcoincore.storage.deleteDatabaseFiles
import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.litecoinkit.LitecoinKit
import java.io.File

internal object MwebFiles {
    fun databaseName(networkType: LitecoinKit.NetworkType, walletId: String): String {
        return "Litecoin-MWEB-${networkType.name}-$walletId"
    }

    fun daemonDataDir(
        mwebDataDir: String,
        networkType: LitecoinKit.NetworkType,
        walletId: String,
        nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
    ): File {
        return File(mwebDataDir, nameResolver.resolve(databaseName(networkType, walletId)))
    }

    fun publicSendDaemonDataDir(
        mwebDataDir: String,
        networkType: LitecoinKit.NetworkType,
        walletId: String,
        nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
    ): File {
        return File(mwebDataDir, nameResolver.resolve("${databaseName(networkType, walletId)}-PublicSend"))
    }

    fun clear(
        dataDir: String,
        mwebDataDir: String,
        networkType: LitecoinKit.NetworkType,
        walletId: String,
        nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
    ) {
        MwebPublicPegInSender.checkCanClear(walletId, networkType)
        deleteDatabaseFiles(dataDir, databaseName(networkType, walletId), nameResolver)
        clearDaemonData(mwebDataDir, networkType, walletId, nameResolver)
    }

    fun clearDaemonData(
        mwebDataDir: String,
        networkType: LitecoinKit.NetworkType,
        walletId: String,
        nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
    ) {
        daemonDataDir(mwebDataDir, networkType, walletId, nameResolver).deleteRecursively()
        publicSendDaemonDataDir(mwebDataDir, networkType, walletId, nameResolver).deleteRecursively()
    }
}

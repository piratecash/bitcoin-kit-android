package io.horizontalsystems.litecoinkit

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.litecoinkit.mweb.MwebFiles
import org.junit.Assert.assertEquals
import org.junit.Test

// Pins the on-disk database names: renaming one orphans every existing user wallet.
class LitecoinKitDatabaseNamesTest {

    @Test
    fun getDatabaseName_apiSyncBip84MainNet_matchesStoredName() {
        assertEquals(
            "Litecoin-MainNet-w1-Api-BIP84",
            LitecoinKit.getDatabaseName(LitecoinKit.NetworkType.MainNet, "w1", SyncMode.Api(), Purpose.BIP84)
        )
    }

    @Test
    fun getDatabaseName_fullSyncBip44TestNet_matchesStoredName() {
        assertEquals(
            "Litecoin-TestNet-w1-Full-BIP44",
            LitecoinKit.getDatabaseName(LitecoinKit.NetworkType.TestNet, "w1", SyncMode.Full(), Purpose.BIP44)
        )
    }

    @Test
    fun sharedDbName_mainNet_matchesStoredName() {
        assertEquals("Litecoin-Shared-MainNet-w1", LitecoinKit.sharedDbName(LitecoinKit.NetworkType.MainNet, "w1"))
    }

    @Test
    fun mwebDatabaseName_mainNet_matchesStoredName() {
        assertEquals("Litecoin-MWEB-MainNet-w1", MwebFiles.databaseName(LitecoinKit.NetworkType.MainNet, "w1"))
    }

    @Test
    fun daemonDataDir_mainNet_matchesStoredDirectoryName() {
        assertEquals(
            "Litecoin-MWEB-MainNet-w1",
            MwebFiles.daemonDataDir("/data", LitecoinKit.NetworkType.MainNet, "w1").name
        )
    }

    @Test
    fun publicSendDaemonDataDir_mainNet_matchesStoredDirectoryName() {
        assertEquals(
            "Litecoin-MWEB-MainNet-w1-PublicSend",
            MwebFiles.publicSendDaemonDataDir("/data", LitecoinKit.NetworkType.MainNet, "w1").name
        )
    }

    @Test
    fun databaseNames_wallet_containsPublicAndMwebDatabasesOnce() {
        val names = LitecoinKit.databaseNames(LitecoinKit.NetworkType.MainNet, "w1")

        assertEquals(14, names.size)
        assertEquals(names.size, names.distinct().size)
        assertEquals("Litecoin-Shared-MainNet-w1", names.first())
        assertEquals("Litecoin-MWEB-MainNet-w1", names.last())
    }
}

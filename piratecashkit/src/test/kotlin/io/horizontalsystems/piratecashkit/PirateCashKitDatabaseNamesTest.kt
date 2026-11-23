package io.horizontalsystems.piratecashkit

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import org.junit.Assert.assertEquals
import org.junit.Test

// Pins the on-disk database names: renaming one orphans every existing user wallet.
class PirateCashKitDatabaseNamesTest {

    @Test
    fun getDatabaseName_apiSyncMainNet_matchesStoredName() {
        assertEquals(
            "PirateCash-MainNet-w1-Api",
            PirateCashKit.getDatabaseName(PirateCashKit.NetworkType.MainNet, "w1", SyncMode.Api())
        )
    }

    @Test
    fun getDatabaseNameCore_apiSyncMainNet_matchesStoredName() {
        assertEquals(
            "PirateCash-MainNet-w1-Api-core",
            PirateCashKit.getDatabaseNameCore(PirateCashKit.NetworkType.MainNet, "w1", SyncMode.Api())
        )
    }

    @Test
    fun databaseNames_wallet_containsCoreAndPirateCashDatabasesForEverySyncMode() {
        val names = PirateCashKit.databaseNames(PirateCashKit.NetworkType.MainNet, "w1")

        assertEquals(6, names.size)
        assertEquals(names.size, names.distinct().size)
        assertEquals("PirateCash-MainNet-w1-Api-core", names.first())
        assertEquals("PirateCash-MainNet-w1-Blockchair", names.last())
    }
}

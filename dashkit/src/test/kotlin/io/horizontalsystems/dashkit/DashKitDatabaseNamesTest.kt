package io.horizontalsystems.dashkit

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import org.junit.Assert.assertEquals
import org.junit.Test

// Pins the on-disk database names: renaming one orphans every existing user wallet.
class DashKitDatabaseNamesTest {

    @Test
    fun getDatabaseName_apiSyncMainNet_matchesStoredName() {
        assertEquals(
            "Dash-MainNet-w1-Api",
            DashKit.getDatabaseName(DashKit.NetworkType.MainNet, "w1", SyncMode.Api())
        )
    }

    @Test
    fun getDatabaseNameCore_apiSyncMainNet_matchesStoredName() {
        assertEquals(
            "Dash-MainNet-w1-Api-core",
            DashKit.getDatabaseNameCore(DashKit.NetworkType.MainNet, "w1", SyncMode.Api())
        )
    }

    @Test
    fun getDatabaseName_fullSyncTestNet_matchesStoredName() {
        assertEquals(
            "Dash-TestNet-w1-Full",
            DashKit.getDatabaseName(DashKit.NetworkType.TestNet, "w1", SyncMode.Full())
        )
    }

    @Test
    fun databaseNames_wallet_containsCoreAndDashDatabasesForEverySyncMode() {
        val names = DashKit.databaseNames(DashKit.NetworkType.MainNet, "w1")

        assertEquals(6, names.size)
        assertEquals(names.size, names.distinct().size)
        assertEquals("Dash-MainNet-w1-Api-core", names.first())
        assertEquals("Dash-MainNet-w1-Blockchair", names.last())
    }
}

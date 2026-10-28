package io.horizontalsystems.cosantakit

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import org.junit.Assert.assertEquals
import org.junit.Test

// Pins the on-disk database names: renaming one orphans every existing user wallet.
class CosantaKitDatabaseNamesTest {

    @Test
    fun getDatabaseName_apiSyncMainNet_matchesStoredName() {
        assertEquals(
            "Cosanta-MainNet-w1-Api",
            CosantaKit.getDatabaseName(CosantaKit.NetworkType.MainNet, "w1", SyncMode.Api())
        )
    }

    @Test
    fun getDatabaseNameCore_apiSyncMainNet_matchesStoredName() {
        assertEquals(
            "Cosanta-MainNet-w1-Api-core",
            CosantaKit.getDatabaseNameCore(CosantaKit.NetworkType.MainNet, "w1", SyncMode.Api())
        )
    }

    @Test
    fun databaseNames_wallet_containsCoreAndCosantaDatabasesForEverySyncMode() {
        val names = CosantaKit.databaseNames(CosantaKit.NetworkType.MainNet, "w1")

        assertEquals(6, names.size)
        assertEquals(names.size, names.distinct().size)
        assertEquals("Cosanta-MainNet-w1-Api-core", names.first())
        assertEquals("Cosanta-MainNet-w1-Blockchair", names.last())
    }
}

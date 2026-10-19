package io.horizontalsystems.ecash

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import org.junit.Assert.assertEquals
import org.junit.Test

// Pins the on-disk database names: renaming one orphans every existing user wallet.
class ECashKitDatabaseNamesTest {

    @Test
    fun getDatabaseName_apiSyncMainNet_matchesStoredName() {
        assertEquals(
            "ECash-MainNet-w1-Api",
            ECashKit.getDatabaseName(ECashKit.NetworkType.MainNet, "w1", SyncMode.Api())
        )
    }

    @Test
    fun databaseNames_wallet_containsOneDatabasePerSyncMode() {
        val names = ECashKit.databaseNames(ECashKit.NetworkType.MainNet, "w1")

        assertEquals(3, names.size)
        assertEquals(names.size, names.distinct().size)
        assertEquals("ECash-MainNet-w1-Api", names.first())
        assertEquals("ECash-MainNet-w1-Blockchair", names.last())
    }
}

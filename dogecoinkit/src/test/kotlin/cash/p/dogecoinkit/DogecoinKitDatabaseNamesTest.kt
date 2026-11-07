package cash.p.dogecoinkit

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import org.junit.Assert.assertEquals
import org.junit.Test

// Pins the on-disk database names: renaming one orphans every existing user wallet.
// Note the deliberate absence of a purpose segment — DogecoinKit is BIP44-only.
class DogecoinKitDatabaseNamesTest {

    @Test
    fun getDatabaseName_apiSyncMainNet_matchesStoredName() {
        assertEquals(
            "Dogecoin-MainNet-w1-Api",
            DogecoinKit.getDatabaseName(DogecoinKit.NetworkType.MainNet, "w1", SyncMode.Api())
        )
    }

    @Test
    fun getDatabaseName_blockchairSyncTestNet_matchesStoredName() {
        assertEquals(
            "Dogecoin-TestNet-w1-Blockchair",
            DogecoinKit.getDatabaseName(DogecoinKit.NetworkType.TestNet, "w1", SyncMode.Blockchair())
        )
    }

    @Test
    fun databaseNames_wallet_containsOneDatabasePerSyncMode() {
        val names = DogecoinKit.databaseNames(DogecoinKit.NetworkType.MainNet, "w1")

        assertEquals(3, names.size)
        assertEquals(names.size, names.distinct().size)
        assertEquals("Dogecoin-MainNet-w1-Api", names.first())
        assertEquals("Dogecoin-MainNet-w1-Blockchair", names.last())
    }
}

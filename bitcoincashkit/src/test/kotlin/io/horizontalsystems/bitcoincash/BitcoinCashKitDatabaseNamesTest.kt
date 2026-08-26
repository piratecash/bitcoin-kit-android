package io.horizontalsystems.bitcoincash

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import org.junit.Assert.assertEquals
import org.junit.Test

// Pins the on-disk database names: renaming one orphans every existing user wallet.
// The lowercase "mainNet" for CoinType.Type0 is back-compatibility with the pre-CoinType naming.
class BitcoinCashKitDatabaseNamesTest {

    @Test
    fun getDatabaseName_coinType0_keepsLegacyMainNetSegment() {
        assertEquals(
            "BitcoinCash-mainNet-w1-Api",
            BitcoinCashKit.getDatabaseName(
                BitcoinCashKit.NetworkType.MainNet(MainNetBitcoinCash.CoinType.Type0),
                "w1",
                SyncMode.Api()
            )
        )
    }

    @Test
    fun getDatabaseName_coinType145_matchesStoredName() {
        assertEquals(
            "BitcoinCash-mainNet-145-w1-Api",
            BitcoinCashKit.getDatabaseName(
                BitcoinCashKit.NetworkType.MainNet(MainNetBitcoinCash.CoinType.Type145),
                "w1",
                SyncMode.Api()
            )
        )
    }

    @Test
    fun getDatabaseName_testNet_matchesStoredName() {
        assertEquals(
            "BitcoinCash-testNet-w1-Full",
            BitcoinCashKit.getDatabaseName(BitcoinCashKit.NetworkType.TestNet, "w1", SyncMode.Full())
        )
    }

    @Test
    fun databaseNames_wallet_containsOneDatabasePerSyncMode() {
        val names = BitcoinCashKit.databaseNames(
            BitcoinCashKit.NetworkType.MainNet(MainNetBitcoinCash.CoinType.Type0),
            "w1"
        )

        assertEquals(3, names.size)
        assertEquals(names.size, names.distinct().size)
        assertEquals("BitcoinCash-mainNet-w1-Api", names.first())
        assertEquals("BitcoinCash-mainNet-w1-Blockchair", names.last())
    }

    @Test
    fun databaseNames_bothCoinTypes_areDisjoint() {
        val type0 = BitcoinCashKit.databaseNames(
            BitcoinCashKit.NetworkType.MainNet(MainNetBitcoinCash.CoinType.Type0),
            "w1"
        )
        val type145 = BitcoinCashKit.databaseNames(
            BitcoinCashKit.NetworkType.MainNet(MainNetBitcoinCash.CoinType.Type145),
            "w1"
        )

        assertEquals(emptyList<String>(), type0.intersect(type145.toSet()).toList())
    }
}

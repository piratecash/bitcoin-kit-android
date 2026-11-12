package io.horizontalsystems.bitcoinkit

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import org.junit.Assert.assertEquals
import org.junit.Test

// Pins the on-disk database names: renaming one orphans every existing user wallet.
class BitcoinKitDatabaseNamesTest {

    @Test
    fun getDatabaseName_apiSyncBip84MainNet_matchesStoredName() {
        assertEquals(
            "Bitcoin-MainNet-w1-Api-BIP84",
            BitcoinKit.getDatabaseName(BitcoinKit.NetworkType.MainNet, "w1", SyncMode.Api(), Purpose.BIP84)
        )
    }

    @Test
    fun getDatabaseName_fullSyncBip44TestNet_matchesStoredName() {
        assertEquals(
            "Bitcoin-TestNet-w1-Full-BIP44",
            BitcoinKit.getDatabaseName(BitcoinKit.NetworkType.TestNet, "w1", SyncMode.Full(), Purpose.BIP44)
        )
    }

    @Test
    fun getDatabaseName_blockchairSync_matchesStoredName() {
        assertEquals(
            "Bitcoin-MainNet-w1-Blockchair-BIP86",
            BitcoinKit.getDatabaseName(BitcoinKit.NetworkType.MainNet, "w1", SyncMode.Blockchair(), Purpose.BIP86)
        )
    }

    @Test
    fun sharedDbName_mainNet_matchesStoredName() {
        assertEquals("Bitcoin-Shared-MainNet-w1", BitcoinKit.sharedDbName(BitcoinKit.NetworkType.MainNet, "w1"))
    }

    @Test
    fun databaseNames_wallet_containsEveryMigratedDatabaseOnce() {
        val names = BitcoinKit.databaseNames(BitcoinKit.NetworkType.MainNet, "w1")

        assertEquals(13, names.size)
        assertEquals(names.size, names.distinct().size)
        assertEquals("Bitcoin-Shared-MainNet-w1", names.first())
        assertEquals("Bitcoin-MainNet-w1-Blockchair-BIP86", names.last())
    }
}

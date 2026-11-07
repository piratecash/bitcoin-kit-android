package io.horizontalsystems.ecash

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IConnectionManagerListener
import io.horizontalsystems.bitcoincore.storage.CoreDatabase
import io.horizontalsystems.bitcoincore.storage.DatabaseMigrationRequiredException
import io.horizontalsystems.hdwalletkit.HDExtendedKey
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hdwalletkit.Mnemonic
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

// Every keyed constructor must forward the key down to CoreDatabase: a dropped key would
// silently open the plaintext database instead of demanding migration.
class ECashKitDatabaseKeyJvmTest {
    private val key = ByteArray(32) { it.toByte() }
    private val networkType = ECashKit.NetworkType.MainNet
    private val syncMode = SyncMode.Api()

    @Test
    fun wordsConstructor_keyOverPlaintextDatabase_requiresMigration() {
        val dir = plaintextWalletDir()

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            ECashKit(dir, key, OfflineConnectionManager, WORDS, "", WALLET, networkType, syncMode = syncMode)
        }
    }

    @Test
    fun seedConstructor_keyOverPlaintextDatabase_requiresMigration() {
        val dir = plaintextWalletDir()

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            ECashKit(dir, key, OfflineConnectionManager, SEED, WALLET, networkType, syncMode = syncMode)
        }
    }

    @Test
    fun extendedKeyConstructor_keyOverPlaintextDatabase_requiresMigration() {
        val dir = plaintextWalletDir()

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            ECashKit(dir, key, OfflineConnectionManager, HDExtendedKey(SEED, Purpose.BIP44), WALLET, networkType, syncMode = syncMode)
        }
    }

    @Test
    fun watchAddressConstructor_keyOverPlaintextDatabase_requiresMigration() {
        val dir = plaintextWalletDir()

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            ECashKit(dir, key, OfflineConnectionManager, ADDRESS, WALLET, networkType, syncMode = syncMode)
        }
    }

    // Room opens the file lazily, so a query is what actually writes the plaintext database to disk.
    private fun plaintextWalletDir(): String {
        val dir = Files.createTempDirectory("ecash-key").toString()
        val database = CoreDatabase.getInstance(dir, ECashKit.getDatabaseName(networkType, WALLET, syncMode))
        try {
            database.peerAddress.hasFresh(emptyList())
        } finally {
            database.close()
        }
        return dir
    }

    private object OfflineConnectionManager : IConnectionManager {
        override val isConnected = false
        override fun addListener(listener: IConnectionManagerListener) = Unit
        override fun removeListener(listener: IConnectionManagerListener) = Unit
        override fun onEnterForeground() = Unit
        override fun onEnterBackground() = Unit
    }

    private companion object {
        const val WALLET = "w1"
        const val ADDRESS = "1PQPheJQSauxRPTxzNMUco1XmoCyPoEJCp"
        val WORDS = List(11) { "abandon" } + "about"
        val SEED: ByteArray = Mnemonic().toSeed(WORDS, "")
    }
}

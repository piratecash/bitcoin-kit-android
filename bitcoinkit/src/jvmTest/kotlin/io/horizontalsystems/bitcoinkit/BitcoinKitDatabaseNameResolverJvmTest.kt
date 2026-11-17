package io.horizontalsystems.bitcoinkit

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IConnectionManagerListener
import io.horizontalsystems.bitcoincore.storage.DatabaseMigrationRequiredException
import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.hdwalletkit.HDExtendedKey
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hdwalletkit.Mnemonic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

// Android keeps the default names forever: a change here makes every existing wallet resync from scratch.
class BitcoinKitDatabaseNameResolverJvmTest {
    private val key = ByteArray(32) { it.toByte() }
    private val resolver = DatabaseNameResolver { "opaque-$it" }
    private val mainNet = BitcoinKit.NetworkType.MainNet
    private val testNet = BitcoinKit.NetworkType.TestNet

    @Test
    fun clear_defaultResolver_removesStoredNamesAndKeepsReleasedLockName() {
        val dir = tempDir()
        val stored = File(dir, STORED_NAME).apply { writeText("db") }

        BitcoinKit.clear(dir.path, mainNet, WALLET)

        assertFalse(stored.exists())
        assertEquals(listOf(".bitcoin-kit-sqlcipher.lock"), dir.list().orEmpty().toList())
    }

    @Test
    fun clear_overrideResolver_removesResolvedNamesAndLeavesNoDefaultName() {
        val dir = tempDir()
        val resolved = File(dir, "opaque-$STORED_NAME").apply { writeText("db") }
        val foreign = File(dir, STORED_NAME).apply { writeText("db") }

        BitcoinKit.clear(dir.path, mainNet, WALLET, resolver)

        assertFalse(resolved.exists())
        assertTrue(foreign.exists())
        assertEquals(
            setOf(STORED_NAME, ".opaque-bitcoin-kit-sqlcipher.lock"),
            dir.list().orEmpty().toSet(),
        )
    }

    @Test
    fun databaseNames_twoNetworks_stayPairwiseDistinctAfterResolve() {
        val defaults = BitcoinKit.databaseNames(mainNet, WALLET) + BitcoinKit.databaseNames(testNet, WALLET)

        assertEquals(defaults.size, defaults.distinct().size)
        assertEquals(defaults.size, defaults.map(resolver::resolve).distinct().size)
    }

    @Test
    fun seedConstructor_defaultResolver_opensStoredName() {
        val dir = dirWithPlaintext(STORED_NAME)

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            BitcoinKit(dir, key, OfflineConnectionManager, SEED, WALLET, mainNet, syncMode = SyncMode.Api(), purpose = Purpose.BIP84)
        }
    }

    @Test
    fun keyedConstructors_overrideResolver_openResolvedName() {
        val dir = dirWithPlaintext("opaque-$STORED_NAME")

        listOf<() -> Any>(
            { BitcoinKit(dir, key, OfflineConnectionManager, WORDS, "", WALLET, mainNet, syncMode = SyncMode.Api(), purpose = Purpose.BIP84, nameResolver = resolver) },
            { BitcoinKit(dir, key, OfflineConnectionManager, SEED, WALLET, mainNet, syncMode = SyncMode.Api(), purpose = Purpose.BIP84, nameResolver = resolver) },
            { BitcoinKit(dir, key, OfflineConnectionManager, HDExtendedKey(SEED, Purpose.BIP84), Purpose.BIP84, WALLET, mainNet, syncMode = SyncMode.Api(), nameResolver = resolver) },
            { BitcoinKit(dir, key, OfflineConnectionManager, ADDRESS, WALLET, mainNet, syncMode = SyncMode.Api(), nameResolver = resolver) },
        ).forEach { construct ->
            assertThrows(DatabaseMigrationRequiredException::class.java) { construct() }
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("bitcoin-name-resolver").toFile()

    // The header alone marks a file as plaintext, which a keyed open rejects before touching SQLCipher.
    private fun dirWithPlaintext(fileName: String): String {
        val dir = tempDir()
        File(dir, fileName).writeBytes("SQLite format 3\u0000".encodeToByteArray())
        return dir.path
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
        const val STORED_NAME = "Bitcoin-MainNet-w1-Api-BIP84"
        const val ADDRESS = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        val WORDS = List(11) { "abandon" } + "about"
        val SEED: ByteArray = Mnemonic().toSeed(WORDS, "")
    }
}

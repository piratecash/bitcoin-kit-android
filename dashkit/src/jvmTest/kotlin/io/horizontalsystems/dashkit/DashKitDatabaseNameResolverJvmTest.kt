package io.horizontalsystems.dashkit

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
class DashKitDatabaseNameResolverJvmTest {
    private val key = ByteArray(32) { it.toByte() }
    private val resolver = DatabaseNameResolver { "opaque-$it" }
    private val mainNet = DashKit.NetworkType.MainNet
    private val testNet = DashKit.NetworkType.TestNet

    @Test
    fun clear_defaultResolver_removesStoredNamesAndKeepsReleasedLockName() {
        val dir = tempDir()
        val stored = File(dir, STORED_NAME).apply { writeText("db") }

        DashKit.clear(dir.path, mainNet, WALLET)

        assertFalse(stored.exists())
        assertEquals(listOf(".bitcoin-kit-sqlcipher.lock"), dir.list().orEmpty().toList())
    }

    @Test
    fun clear_overrideResolver_removesResolvedNamesAndLeavesNoDefaultName() {
        val dir = tempDir()
        val resolved = File(dir, "opaque-$STORED_NAME").apply { writeText("db") }
        val foreign = File(dir, STORED_NAME).apply { writeText("db") }

        DashKit.clear(dir.path, mainNet, WALLET, resolver)

        assertFalse(resolved.exists())
        assertTrue(foreign.exists())
        assertEquals(
            setOf(STORED_NAME, ".opaque-bitcoin-kit-sqlcipher.lock"),
            dir.list().orEmpty().toSet(),
        )
    }

    @Test
    fun databaseNames_twoNetworks_stayPairwiseDistinctAfterResolve() {
        val defaults = DashKit.databaseNames(mainNet, WALLET) + DashKit.databaseNames(testNet, WALLET)

        assertEquals(defaults.size, defaults.distinct().size)
        assertEquals(defaults.size, defaults.map(resolver::resolve).distinct().size)
    }

    @Test
    fun seedConstructor_defaultResolver_opensStoredName() {
        val dir = dirWithPlaintext(STORED_NAME)

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            DashKit(dir, key, OfflineConnectionManager, SEED, WALLET, mainNet, syncMode = SyncMode.Api())
        }
    }

    @Test
    fun keyedConstructors_overrideResolver_openResolvedName() {
        val dir = dirWithPlaintext("opaque-$STORED_NAME")

        listOf<() -> Any>(
            { DashKit(dir, key, OfflineConnectionManager, WORDS, "", WALLET, mainNet, syncMode = SyncMode.Api(), nameResolver = resolver) },
            { DashKit(dir, key, OfflineConnectionManager, SEED, WALLET, mainNet, syncMode = SyncMode.Api(), nameResolver = resolver) },
            { DashKit(dir, key, OfflineConnectionManager, HDExtendedKey(SEED, Purpose.BIP44), WALLET, mainNet, syncMode = SyncMode.Api(), nameResolver = resolver) },
            { DashKit(dir, key, OfflineConnectionManager, ADDRESS, WALLET, mainNet, syncMode = SyncMode.Api(), nameResolver = resolver) },
        ).forEach { construct ->
            assertThrows(DatabaseMigrationRequiredException::class.java) { construct() }
        }
    }

    @Test
    fun keyedConstructor_overrideResolver_opensResolvedKitDatabase() {
        val dir = dirWithPlaintext("opaque-Dash-MainNet-w1-Api")

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            DashKit(dir, key, OfflineConnectionManager, SEED, WALLET, mainNet, syncMode = SyncMode.Api(), nameResolver = resolver)
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("dash-name-resolver").toFile()

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
        const val STORED_NAME = "Dash-MainNet-w1-Api-core"
        const val ADDRESS = "XcF5mKwWsiv3k394GBQNpYAuk3CVJ48Xnp"
        val WORDS = List(11) { "abandon" } + "about"
        val SEED: ByteArray = Mnemonic().toSeed(WORDS, "")
    }
}

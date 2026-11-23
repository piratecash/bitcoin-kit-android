package io.horizontalsystems.litecoinkit

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IConnectionManagerListener
import io.horizontalsystems.bitcoincore.storage.DatabaseMigrationRequiredException
import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.hdwalletkit.HDExtendedKey
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.litecoinkit.mweb.CoroutineMwebDispatcherProvider
import io.horizontalsystems.litecoinkit.mweb.MwebConfig
import io.horizontalsystems.litecoinkit.mweb.MwebFiles
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

// Android keeps the default names forever: a change here makes every existing wallet resync from scratch.
class LitecoinKitDatabaseNameResolverJvmTest {
    private val key = ByteArray(32) { it.toByte() }
    private val resolver = DatabaseNameResolver { "opaque-$it" }
    private val mainNet = LitecoinKit.NetworkType.MainNet
    private val testNet = LitecoinKit.NetworkType.TestNet

    @Test
    fun clear_defaultResolver_removesStoredNamesAndDaemonDirectories() {
        val dir = tempDir()
        val mwebDir = tempDir()
        val stored = File(dir, STORED_NAME).apply { writeText("db") }
        val mwebDatabase = File(dir, MWEB_NAME).apply { writeText("db") }
        val daemon = File(mwebDir, MWEB_NAME).apply { mkdirs() }
        val publicSend = File(mwebDir, "$MWEB_NAME-PublicSend").apply { mkdirs() }

        LitecoinKit.clear(dir.path, mwebDir.path, mainNet, WALLET)

        listOf(stored, mwebDatabase, daemon, publicSend).forEach { assertFalse(it.path, it.exists()) }
        assertEquals(listOf(".bitcoin-kit-sqlcipher.lock"), dir.list().orEmpty().toList())
    }

    @Test
    fun clear_overrideResolver_removesResolvedNamesAndLeavesDefaultOnes() {
        val dir = tempDir()
        val mwebDir = tempDir()
        val foreign = File(dir, STORED_NAME).apply { writeText("db") }
        val foreignDaemon = File(mwebDir, MWEB_NAME).apply { mkdirs() }
        File(dir, "opaque-$STORED_NAME").writeText("db")
        File(dir, "opaque-$MWEB_NAME").writeText("db")
        File(mwebDir, "opaque-$MWEB_NAME").mkdirs()
        File(mwebDir, "opaque-$MWEB_NAME-PublicSend").mkdirs()

        LitecoinKit.clear(dir.path, mwebDir.path, mainNet, WALLET, resolver)

        assertTrue(foreign.exists())
        assertTrue(foreignDaemon.exists())
        assertEquals(setOf(STORED_NAME, ".opaque-bitcoin-kit-sqlcipher.lock"), dir.list().orEmpty().toSet())
        assertEquals(setOf(MWEB_NAME), mwebDir.list().orEmpty().toSet())
    }

    @Test
    fun clearMweb_overrideResolver_removesOnlyResolvedMwebFiles() {
        val dir = tempDir()
        val mwebDir = tempDir()
        val publicDatabase = File(dir, "opaque-$STORED_NAME").apply { writeText("db") }
        File(dir, "opaque-$MWEB_NAME").writeText("db")
        File(mwebDir, "opaque-$MWEB_NAME").mkdirs()
        File(mwebDir, "opaque-$MWEB_NAME-PublicSend").mkdirs()

        LitecoinKit.clearMweb(dir.path, mwebDir.path, mainNet, WALLET, resolver)

        assertTrue(publicDatabase.exists())
        assertFalse(File(dir, "opaque-$MWEB_NAME").exists())
        assertEquals(emptyList<String>(), mwebDir.list().orEmpty().toList())
    }

    @Test
    fun daemonDataDirs_overrideResolver_useResolvedDirectoryNames() {
        assertEquals("opaque-$MWEB_NAME", MwebFiles.daemonDataDir("/data", mainNet, WALLET, resolver).name)
        assertEquals(
            "opaque-$MWEB_NAME-PublicSend",
            MwebFiles.publicSendDaemonDataDir("/data", mainNet, WALLET, resolver).name,
        )
    }

    @Test
    fun databaseNames_twoNetworks_stayPairwiseDistinctAfterResolve() {
        val defaults = LitecoinKit.databaseNames(mainNet, WALLET) + LitecoinKit.databaseNames(testNet, WALLET)

        assertEquals(defaults.size, defaults.distinct().size)
        assertEquals(defaults.size, defaults.map(resolver::resolve).distinct().size)
    }

    @Test
    fun keyedConstructors_overrideResolver_openResolvedName() {
        val dir = dirWithPlaintext("opaque-$STORED_NAME")
        val mwebDir = tempDir().path

        listOf<() -> Any>(
            { LitecoinKit(dir, mwebDir, key, OfflineConnectionManager, WORDS, "", WALLET, mainNet, syncMode = SyncMode.Api(), purpose = Purpose.BIP84, nameResolver = resolver) },
            { LitecoinKit(dir, mwebDir, key, OfflineConnectionManager, SEED, WALLET, mainNet, syncMode = SyncMode.Api(), purpose = Purpose.BIP84, nameResolver = resolver) },
            { LitecoinKit(dir, mwebDir, key, OfflineConnectionManager, HDExtendedKey(SEED, Purpose.BIP84), Purpose.BIP84, WALLET, mainNet, syncMode = SyncMode.Api(), nameResolver = resolver) },
            { LitecoinKit(dir, mwebDir, key, OfflineConnectionManager, ADDRESS, WALLET, mainNet, syncMode = SyncMode.Api(), nameResolver = resolver) },
        ).forEach { construct ->
            assertThrows(DatabaseMigrationRequiredException::class.java) { construct() }
        }
    }

    @Test
    fun sharedPeerGroup_overrideResolver_opensResolvedName() {
        val dir = dirWithPlaintext("opaque-Litecoin-Shared-MainNet-w1")

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            LitecoinKit.getOrCreateSharedPeerGroup(dir, key, OfflineConnectionManager, WALLET, mainNet, nameResolver = resolver)
        }
    }

    @Test
    fun mwebConstructor_overrideResolver_opensResolvedMwebDatabase() {
        val dir = dirWithPlaintext("opaque-$MWEB_NAME")
        val config = MwebConfig(CoroutineMwebDispatcherProvider(Dispatchers.IO))

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            LitecoinKit(
                dir, tempDir().path, key, OfflineConnectionManager, SEED, WALLET, mainNet,
                syncMode = SyncMode.Api(), purpose = Purpose.BIP84, mwebConfig = config, nameResolver = resolver,
            )
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("litecoin-name-resolver").toFile()

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
        const val STORED_NAME = "Litecoin-MainNet-w1-Api-BIP84"
        const val MWEB_NAME = "Litecoin-MWEB-MainNet-w1"
        const val ADDRESS = "ltc1qw508d6qejxtdg4y5r3zarvary0c5xw7kgmn4n9"
        val WORDS = List(11) { "abandon" } + "about"
        val SEED: ByteArray = Mnemonic().toSeed(WORDS, "")
    }
}

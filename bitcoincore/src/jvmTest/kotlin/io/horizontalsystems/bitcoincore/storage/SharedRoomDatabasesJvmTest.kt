package io.horizontalsystems.bitcoincore.storage

import io.horizontalsystems.bitcoincore.models.PeerAddress
import io.horizontalsystems.databasenaming.DatabaseNameResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class SharedRoomDatabasesJvmTest {
    private val key = ByteArray(32) { it.toByte() }
    private val name = "core.db"
    private val dataDir: File = Files.createTempDirectory("shared-room").toFile()

    @After
    fun tearDown() {
        deleteDatabaseFiles(dataDir.path, name)
        dataDir.deleteRecursively()
    }

    @Test
    fun getInstance_samePathAndKey_returnsSameInstance() {
        assertSame(open(key), open(key))
    }

    @Test
    fun getInstance_pathThroughSymlinkedDirectory_returnsSameInstance() {
        val link = Files.createSymbolicLink(dataDir.toPath().resolveSibling("${dataDir.name}-link"), dataDir.toPath())
        try {
            assertSame(open(key), CoreDatabase.getInstance(link.toString(), name, key))
        } finally {
            Files.delete(link)
        }
    }

    @Test
    fun getInstance_differentKey_buildsAgainAndThrowsKeyMismatch() {
        open(key).peerAddress.hasFresh(emptyList())

        assertThrows(DatabaseKeyMismatchException::class.java) { open(ByteArray(32) { 1 }) }
    }

    @Test
    fun clearDatabases_cachedInstance_nextGetInstanceIsFreshAndOldStaysOpen() {
        val old = open(key).apply { peerAddress.insertAll(listOf(PeerAddress("10.0.0.1"))) }

        DatabaseEncryption.clearDatabases(dataDir.path, listOf(name), MIGRATION_ID)
        val fresh = open(key)

        assertNotSame(old, fresh)
        assertNull(fresh.peerAddress.getLeastScoreFastest(emptyList()))
        old.peerAddress.insertAll(listOf(PeerAddress("10.0.0.2")))
    }

    @Test
    fun clearDatabases_throws_keepsCachedInstance() {
        val cached = open(key).apply { peerAddress.hasFresh(emptyList()) }

        // The migration lock is taken in-process, so the clear sees a concurrent migration and gives up.
        FileOutputStream(File(dataDir, ".bitcoin-kit-sqlcipher.lock"), true).channel.use { channel ->
            channel.lock().use {
                assertThrows(DatabaseMigrationConflictException::class.java) {
                    DatabaseEncryption.clearDatabases(dataDir.path, listOf(name), MIGRATION_ID)
                }
            }
        }

        assertSame(cached, open(key))
    }

    @Test
    fun deleteDatabaseFiles_cachedInstance_nextGetInstanceIsFresh() {
        val old = open(key).apply { peerAddress.insertAll(listOf(PeerAddress("10.0.0.1"))) }

        deleteDatabaseFiles(dataDir.path, name)
        val fresh = open(key)

        assertNotSame(old, fresh)
        assertNull(fresh.peerAddress.getLeastScoreFastest(emptyList()))
    }

    @Test
    fun migrateDatabases_alreadyEncryptedGroup_keepsCachedInstance() = runBlocking {
        val cached = open(key).apply { peerAddress.hasFresh(emptyList()) }

        val result = DatabaseEncryption.migrateDatabases(dataDir.path, listOf(name), MIGRATION_ID, key)

        assertEquals(0, result.migratedDatabaseCount)
        assertSame(cached, open(key))
    }

    @Test
    fun migrateDatabases_plaintextGroup_keyedOpensNewAndUnkeyedRequiresKey() = runBlocking {
        val plaintext = open(null).apply { peerAddress.insertAll(listOf(PeerAddress("10.0.0.1"))) }

        val result = DatabaseEncryption.migrateDatabases(dataDir.path, listOf(name), MIGRATION_ID, key)

        assertEquals(1, result.migratedDatabaseCount)
        val encrypted = open(key)
        assertNotSame(plaintext, encrypted)
        assertEquals("10.0.0.1", encrypted.peerAddress.getLeastScoreFastest(emptyList())?.ip)
        assertThrows(DatabaseKeyRequiredException::class.java) { open(null) }
        Unit
    }

    @Test
    fun getInstance_cachedPlaintextWithPendingMigration_throwsMigrationInProgress() {
        open(null).peerAddress.hasFresh(emptyList())
        // Same name a migration interrupted mid-way leaves behind.
        val manifest = File(dataDir, ".bitcoin-kit-sqlcipher-$MIGRATION_ID.json").apply { writeText("{}") }
        try {
            assertThrows(DatabaseMigrationInProgressException::class.java) { open(null) }
        } finally {
            manifest.delete()
        }
    }

    @Test
    fun getInstance_cachedPlaintextReplacedByEncryptedWithoutEviction_throwsKeyRequired() = runBlocking {
        open(null).peerAddress.insertAll(listOf(PeerAddress("10.0.0.1")))

        // The raw delegate skips DatabaseEncryption's eviction, leaving the plaintext instance cached.
        bitcoinKitDatabases(DatabaseNameResolver.Identity).migrateDatabases(dataDir.path, listOf(name), MIGRATION_ID, key)

        assertThrows(DatabaseKeyRequiredException::class.java) { open(null) }
        Unit
    }

    private fun open(databaseKey: ByteArray?): CoreDatabase = CoreDatabase.getInstance(dataDir.path, name, databaseKey)

    private companion object {
        const val MIGRATION_ID = "wallet"
    }
}

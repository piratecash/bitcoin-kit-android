package io.horizontalsystems.sqlcipher.room

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase as SqlCipherSQLiteDatabase

@RunWith(AndroidJUnit4::class)
class SqlCipherDatabasesAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }
    private val databases = SqlCipherDatabases("test-kit")
    private val name = "sqlcipher-room-${UUID.randomUUID()}.db"
    private val file = context.getDatabasePath(name)
    private val dataDir = requireNotNull(file.parentFile).also { it.mkdirs() }.path

    @After
    fun tearDown() {
        databases.clearDatabases(dataDir, listOf(name), migrationId = name)
    }

    @Test
    fun migrateDatabases_plaintextDatabase_encryptsAndPreservesDataAndUserVersion() = runBlocking {
        createPlaintextDatabase()

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(isPlaintextDatabase(file))
        val literal = databaseKeyLiteral(key)
        SqlCipherSQLiteDatabase.openDatabase(file.path, literal, null, SqlCipherSQLiteDatabase.OPEN_READONLY, null)
            .use { database ->
                database.rawQuery("SELECT value FROM sample", emptyArray<String>()).use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(SAMPLE_VALUE, cursor.getString(0))
                }
                assertEquals(USER_VERSION, database.version)
            }
    }

    @Test
    fun migrateDatabases_plaintextDatabase_opensThroughSupportOpenHelperFactory() = runBlocking {
        createPlaintextDatabase()

        migrate(key)

        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(NoOpCallback())
            .build()
        SupportOpenHelperFactory(databaseKeyLiteral(key)).create(configuration).use { helper ->
            helper.readableDatabase.query("SELECT value FROM sample").use { cursor ->
                cursor.moveToFirst()
                assertEquals(SAMPLE_VALUE, cursor.getString(0))
            }
        }
    }

    @Test
    fun migrateDatabases_alreadyEncrypted_reportsAlreadyEncrypted() = runBlocking {
        createPlaintextDatabase()
        migrate(key)
        val encryptedBytes = file.readBytes()

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(0, 1), result)
        assertArrayEquals(encryptedBytes, file.readBytes())
    }

    @Test
    fun migrateDatabases_wrongKey_throwsKeyMismatchAndKeepsFile() {
        createPlaintextDatabase()
        runBlocking { migrate(key) }
        val encryptedBytes = file.readBytes()

        assertThrows(DatabaseKeyMismatchException::class.java) {
            runBlocking { migrate(otherKey) }
        }

        assertArrayEquals(encryptedBytes, file.readBytes())
    }

    @Test
    fun encrypted_wrongKey_throwsKeyMismatch() {
        createPlaintextDatabase()
        runBlocking { migrate(key) }

        assertThrows(DatabaseKeyMismatchException::class.java) {
            databases.encrypted(roomBuilder(), file.path, otherKey)
        }
    }

    @Test
    fun encrypted_plaintextDatabase_throwsMigrationRequired() {
        createPlaintextDatabase()

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            databases.encrypted(roomBuilder(), file.path, key)
        }
    }

    private suspend fun migrate(databaseKey: ByteArray): DatabaseMigrationResult =
        databases.migrateDatabases(dataDir, listOf(name), migrationId = name, databaseKey = databaseKey)

    private fun createPlaintextDatabase() {
        FrameworkSQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL("CREATE TABLE sample(value TEXT NOT NULL)")
            database.execSQL("INSERT INTO sample VALUES('$SAMPLE_VALUE')")
            database.version = USER_VERSION
        }
    }

    // build() is never called, so the missing @Database and generated _Impl do not matter.
    private fun roomBuilder(): RoomDatabase.Builder<TestDatabase> =
        Room.databaseBuilder(context, TestDatabase::class.java, file.path)

    abstract class TestDatabase : RoomDatabase()

    private class NoOpCallback : SupportSQLiteOpenHelper.Callback(USER_VERSION) {
        override fun onCreate(db: SupportSQLiteDatabase) = Unit
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val SAMPLE_VALUE = "core"
        const val USER_VERSION = 7
    }
}

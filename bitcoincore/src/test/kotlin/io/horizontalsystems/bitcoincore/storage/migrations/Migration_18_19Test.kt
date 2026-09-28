package io.horizontalsystems.bitcoincore.storage.migrations

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Migration_18_19 only rewrites row values via raw SQL (no schema change to PublicKey /
 * TransactionOutput / BlockHashPublicKey), so this test database declares an unrelated marker
 * entity only to satisfy Room's non-empty entities requirement: assertions read the migrated
 * tables directly through the support database, the same way the migration itself does.
 */
@Entity(tableName = "MigrationTestMarker")
internal class MigrationTestMarkerEntity(@PrimaryKey val id: Int = 0)

@Database(version = 19, entities = [MigrationTestMarkerEntity::class], exportSchema = false)
internal abstract class Migration18To19TestDatabase : RoomDatabase()

@RunWith(RobolectricTestRunner::class)
class Migration_18_19Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migrate_swappedPublicKeyPaths_exchangesPathsWithoutCollision() {
        seedV18Database(includeTransactionOutputTable = true) { db ->
            insertPublicKey(db, path = "0/0/5", marker = "A")
            insertPublicKey(db, path = "0/1/5", marker = "B")
        }

        val db = openMigratedDatabase().openHelper.writableDatabase

        assertEquals("0/1/5", pathByMarker(db, "A"))
        assertEquals("0/0/5", pathByMarker(db, "B"))
    }

    @Test
    fun migrate_transactionOutputAndBlockHashPublicKey_flipsChangeSegmentInPath() {
        seedV18Database(includeTransactionOutputTable = true) { db ->
            db.execSQL(
                "INSERT INTO `TransactionOutput` (`transactionHash`, `index`, `publicKeyPath`) VALUES (?, 4, '0/0/7')",
                arrayOf(byteArrayOf(1, 2, 3))
            )
            db.execSQL(
                "INSERT INTO `BlockHashPublicKey` (`blockHash`, `publicKeyPath`) VALUES (?, '0/0/2')",
                arrayOf(byteArrayOf(4, 5, 6))
            )
        }

        val db = openMigratedDatabase().openHelper.writableDatabase

        db.query("SELECT `publicKeyPath` FROM `TransactionOutput`").use { cursor ->
            cursor.moveToFirst()
            assertEquals("0/1/7", cursor.getString(0))
        }
        db.query("SELECT `publicKeyPath` FROM `BlockHashPublicKey`").use { cursor ->
            cursor.moveToFirst()
            assertEquals("0/1/2", cursor.getString(0))
        }
    }

    @Test
    fun migrate_laterStepFails_rollsBackEarlierStepWrite() {
        seedV18Database(includeTransactionOutputTable = false) { db ->
            insertPublicKey(db, path = "0/0/9", marker = "X")
        }

        assertThrows(Throwable::class.java) { openMigratedDatabase().openHelper.writableDatabase }

        openV18Database().use { db ->
            assertEquals(18, db.version)
            assertEquals("0/0/9", pathByMarker(db, "X"))
        }
    }

    private fun openMigratedDatabase(): Migration18To19TestDatabase {
        return Room.databaseBuilder(context, Migration18To19TestDatabase::class.java, DB_NAME)
            .addMigrations(Migration_18_19)
            .allowMainThreadQueries()
            .build()
    }

    private fun openV18Database(): SupportSQLiteDatabase =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(18) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    }
                )
                .build()
        ).writableDatabase

    private fun insertPublicKey(database: SupportSQLiteDatabase, path: String, marker: String) {
        database.execSQL("INSERT INTO `PublicKey` (`path`, `marker`) VALUES (?, ?)", arrayOf(path, marker))
    }

    private fun pathByMarker(database: SupportSQLiteDatabase, marker: String): String {
        database.query("SELECT `path` FROM `PublicKey` WHERE `marker` = ?", arrayOf(marker)).use { cursor ->
            cursor.moveToFirst()
            return cursor.getString(0)
        }
    }

    private fun seedV18Database(includeTransactionOutputTable: Boolean, insertRows: (SupportSQLiteDatabase) -> Unit) {
        context.deleteDatabase(DB_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(18) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createV18Schema(db, includeTransactionOutputTable)
                            insertRows(db)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    }
                )
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private fun createV18Schema(database: SupportSQLiteDatabase, includeTransactionOutputTable: Boolean) {
        // Migration_18_19 never touches this table, so Room's post-migration validation only
        // passes if it already exists with the marker entity's exact shape before migrate() runs.
        database.execSQL("CREATE TABLE IF NOT EXISTS `MigrationTestMarker` (`id` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `PublicKey` (`path` TEXT NOT NULL, `marker` TEXT NOT NULL, PRIMARY KEY(`path`))"
        )
        if (includeTransactionOutputTable) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `TransactionOutput` (
                    `transactionHash` BLOB NOT NULL,
                    `index` INTEGER NOT NULL,
                    `publicKeyPath` TEXT,
                    PRIMARY KEY(`transactionHash`, `index`)
                )
                """.trimIndent()
            )
        }
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `BlockHashPublicKey` (
                `blockHash` BLOB NOT NULL,
                `publicKeyPath` TEXT NOT NULL,
                PRIMARY KEY(`blockHash`, `publicKeyPath`)
            )
            """.trimIndent()
        )
    }

    private companion object {
        const val DB_NAME = "migration-18-19-test"
    }
}

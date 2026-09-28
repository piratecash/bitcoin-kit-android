package io.horizontalsystems.bitcoincore.storage.migrations

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.models.TransactionMetadata
import io.horizontalsystems.bitcoincore.models.TransactionType
import io.horizontalsystems.bitcoincore.models.TransactionTypeConverter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Isolated test database pinned at v13: CoreDatabase itself can't be opened at intermediate
 * versions because it always validates its entities against their current (v33) shape, and its
 * registered migration chain has a gap above v20 covered only by destructive fallback.
 */
@Database(version = 13, entities = [TransactionMetadata::class], exportSchema = false)
@TypeConverters(TransactionTypeConverter::class)
internal abstract class Migration12To13TestDatabase : RoomDatabase()

@RunWith(RobolectricTestRunner::class)
class Migration_12_13Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migrate_incomingTransactionWithMineOutput_createsMetadataAndClearsInvalidTransactions() {
        seedV12Database()

        val database = openMigratedDatabase()

        // FullTransaction recomputes the transaction hash by default (forceHashUpdate = true),
        // so the stored TransactionMetadata.transactionHash won't equal the seeded TX_HASH;
        // read the single row directly instead of filtering by hash.
        database.openHelper.writableDatabase
            .query("SELECT `transactionHash`, `amount`, `type`, `fee` FROM `TransactionMetadata`").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals(SHA256D_HASH_SIZE, cursor.getBlob(0).size)
                assertEquals(1_000L, cursor.getLong(1))
                assertEquals(TransactionType.Incoming.value, cursor.getInt(2))
                assertTrue(cursor.isNull(3))
            }
        assertEquals(0, countRows(database, "InvalidTransaction"))

        database.close()
    }

    private fun openMigratedDatabase(): Migration12To13TestDatabase {
        return Room.databaseBuilder(context, Migration12To13TestDatabase::class.java, DB_NAME)
            .addMigrations(Migration_12_13)
            .allowMainThreadQueries()
            .build()
    }

    private fun countRows(database: Migration12To13TestDatabase, table: String): Int {
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private fun seedV12Database() {
        context.deleteDatabase(DB_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(12) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createV12Schema(db)
                            insertV12Rows(db)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    }
                )
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private fun createV12Schema(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `Transaction` (
                `uid` TEXT NOT NULL,
                `hash` BLOB NOT NULL,
                `blockHash` BLOB,
                `version` INTEGER NOT NULL,
                `lockTime` INTEGER NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `order` INTEGER NOT NULL,
                `isMine` INTEGER NOT NULL,
                `isOutgoing` INTEGER NOT NULL,
                `segwit` INTEGER NOT NULL,
                `status` INTEGER NOT NULL,
                `serializedTxInfo` TEXT NOT NULL,
                `conflictingTxHash` BLOB,
                `rawTransaction` TEXT,
                PRIMARY KEY(`hash`)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `TransactionInput` (
                `transactionHash` BLOB NOT NULL,
                `keyHash` BLOB,
                `address` TEXT,
                `witness` TEXT NOT NULL,
                `previousOutputTxHash` BLOB NOT NULL,
                `previousOutputIndex` INTEGER NOT NULL,
                `sigScript` BLOB NOT NULL,
                `sequence` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `TransactionOutput` (
                `value` INTEGER NOT NULL,
                `lockingScript` BLOB NOT NULL,
                `redeemScript` BLOB,
                `index` INTEGER NOT NULL,
                `transactionHash` BLOB NOT NULL,
                `publicKeyPath` TEXT,
                `changeOutput` INTEGER NOT NULL,
                `scriptType` INTEGER,
                `keyHash` BLOB,
                `address` TEXT,
                `failedToSpend` INTEGER NOT NULL DEFAULT 0,
                `pluginId` INTEGER,
                `pluginData` TEXT,
                PRIMARY KEY(`transactionHash`, `index`)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `InvalidTransaction` (
                `hash` BLOB NOT NULL,
                `blockHash` BLOB,
                `version` INTEGER NOT NULL,
                `lockTime` INTEGER NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `order` INTEGER NOT NULL,
                `isMine` INTEGER NOT NULL,
                `isOutgoing` INTEGER NOT NULL,
                `segwit` INTEGER NOT NULL,
                `status` INTEGER NOT NULL,
                `serializedTxInfo` TEXT NOT NULL,
                `conflictingTxHash` BLOB,
                `rawTransaction` TEXT,
                PRIMARY KEY(`hash`)
            )
            """.trimIndent()
        )
    }

    /**
     * One incoming transaction: its single output belongs to us (non-null publicKeyPath), its
     * single input spends an output we don't recognize. Every nullable v12 column that
     * Migration_12_13 reads (blockHash, conflictingTxHash, rawTransaction, redeemScript, keyHash,
     * address, pluginId, pluginData) is left NULL to exercise the isNull guards.
     */
    private fun insertV12Rows(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO `Transaction` (
                `uid`, `hash`, `blockHash`, `version`, `lockTime`, `timestamp`, `order`,
                `isMine`, `isOutgoing`, `segwit`, `status`, `serializedTxInfo`,
                `conflictingTxHash`, `rawTransaction`
            ) VALUES ('tx-1', ?, NULL, 1, 0, 1000, 0, 0, 0, 0, 2, '', NULL, NULL)
            """.trimIndent(),
            arrayOf(TX_HASH)
        )
        database.execSQL(
            """
            INSERT INTO `TransactionInput` (
                `transactionHash`, `keyHash`, `address`, `witness`,
                `previousOutputTxHash`, `previousOutputIndex`, `sigScript`, `sequence`
            ) VALUES (?, NULL, NULL, '', ?, 0, ?, 4294967295)
            """.trimIndent(),
            arrayOf(TX_HASH, UNRELATED_OUTPUT_HASH, byteArrayOf(1))
        )
        database.execSQL(
            """
            INSERT INTO `TransactionOutput` (
                `value`, `lockingScript`, `redeemScript`, `index`, `transactionHash`,
                `publicKeyPath`, `changeOutput`, `scriptType`, `keyHash`, `address`,
                `failedToSpend`, `pluginId`, `pluginData`
            ) VALUES (1000, ?, NULL, 0, ?, '0/0/0', 0, 1, NULL, NULL, 0, NULL, NULL)
            """.trimIndent(),
            arrayOf(byteArrayOf(1, 2), TX_HASH)
        )
        database.execSQL(
            """
            INSERT INTO `InvalidTransaction` (
                `hash`, `blockHash`, `version`, `lockTime`, `timestamp`, `order`,
                `isMine`, `isOutgoing`, `segwit`, `status`, `serializedTxInfo`,
                `conflictingTxHash`, `rawTransaction`
            ) VALUES (?, NULL, 1, 0, 1, 0, 0, 0, 0, 3, '', NULL, NULL)
            """.trimIndent(),
            arrayOf(byteArrayOf(5, 5, 5, 5))
        )
    }

    private companion object {
        const val DB_NAME = "migration-12-13-test"
        const val SHA256D_HASH_SIZE = 32
        // HashBytes.hashCode() reads bytes[0..3], so test hashes need at least 4 bytes.
        val TX_HASH = byteArrayOf(1, 2, 3, 4)
        val UNRELATED_OUTPUT_HASH = byteArrayOf(9, 9, 9, 9)
    }
}

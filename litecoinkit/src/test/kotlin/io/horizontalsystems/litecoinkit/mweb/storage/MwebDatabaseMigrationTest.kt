package io.horizontalsystems.litecoinkit.mweb.storage

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.litecoinkit.mweb.MwebTransactionKind
import io.horizontalsystems.litecoinkit.mweb.MwebTransactionType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MwebDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migration3ToLatest_legacyOutgoingWithoutCreatedOutputs_preservesConfirmedHistory() {
        createV3Database()

        val database = Room.databaseBuilder(context, MwebDatabase::class.java, DB_NAME)
            .addMigrations(*MwebDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        val transactions = MwebRoomStorage(database).localTransactions().associateBy { it.uid }

        transactions.getValue("legacy-peg-out").let { transaction ->
            assertEquals(400, transaction.height)
            assertEquals(1_000L, transaction.timestamp)
            assertFalse(transaction.pending)
        }
        transactions.getValue("peg-in").let { transaction ->
            assertNull(transaction.height)
            assertTrue(transaction.pending)
        }
        transactions.getValue("pending-with-change").let { transaction ->
            assertNull(transaction.height)
            assertTrue(transaction.pending)
        }

        database.close()
    }

    @Test
    fun migration4To5_existingUtxoSyncHeight_setsDeliveryHeightWithSafetyReplay() {
        createV4Database(mwebUtxosHeight = 3_107_750)

        val database = Room.databaseBuilder(context, MwebDatabase::class.java, DB_NAME)
            .addMigrations(*MwebDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val storage = MwebRoomStorage(database)

        assertEquals(3_104_870, storage.utxoDeliveryHeight())
        database.close()
    }

    @Test
    fun migration4To5_lowUtxoSyncHeight_clampsDeliveryHeightToZero() {
        createV4Database(mwebUtxosHeight = 2_000)

        val database = Room.databaseBuilder(context, MwebDatabase::class.java, DB_NAME)
            .addMigrations(*MwebDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val storage = MwebRoomStorage(database)

        assertEquals(0, storage.utxoDeliveryHeight())
        database.close()
    }

    private fun createV3Database() {
        context.deleteDatabase(DB_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createV3Schema(db)
                            insertV3Rows(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    }
                )
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private fun createV4Database(mwebUtxosHeight: Int) {
        context.deleteDatabase(DB_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createV4Schema(db)
                            insertV4State(db, mwebUtxosHeight)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    }
                )
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private fun createV3Schema(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `MwebAddress` (
                `index` INTEGER NOT NULL,
                `address` TEXT NOT NULL,
                `used` INTEGER NOT NULL,
                PRIMARY KEY(`index`)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `MwebUtxo` (
                `outputId` TEXT NOT NULL,
                `address` TEXT NOT NULL,
                `addressIndex` INTEGER NOT NULL,
                `value` INTEGER NOT NULL,
                `height` INTEGER NOT NULL,
                `blockTime` INTEGER NOT NULL,
                `spent` INTEGER NOT NULL,
                PRIMARY KEY(`outputId`)
            )
            """.trimIndent()
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_MwebUtxo_spent` ON `MwebUtxo` (`spent`)")
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `MwebState` (
                `id` INTEGER NOT NULL,
                `blockHeaderHeight` INTEGER NOT NULL,
                `mwebHeaderHeight` INTEGER NOT NULL,
                `mwebUtxosHeight` INTEGER NOT NULL,
                `lastSyncedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `MwebPendingTransaction` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `rawTransaction` BLOB NOT NULL,
                `createdOutputIds` TEXT NOT NULL,
                `canonicalTransactionHash` TEXT,
                `timestamp` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `MwebOutgoingTransaction` (
                `uid` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `amount` INTEGER NOT NULL,
                `fee` INTEGER,
                `destinationAddress` TEXT,
                `canonicalTransactionHash` TEXT,
                `createdOutputIds` TEXT NOT NULL,
                `spentOutputIds` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                PRIMARY KEY(`uid`)
            )
            """.trimIndent()
        )
    }

    private fun createV4Schema(database: SupportSQLiteDatabase) {
        createV3Schema(database)
        database.execSQL("ALTER TABLE `MwebOutgoingTransaction` ADD COLUMN `confirmedHeight` INTEGER")
        database.execSQL("ALTER TABLE `MwebOutgoingTransaction` ADD COLUMN `confirmedTimestamp` INTEGER")
    }

    private fun insertV4State(database: SupportSQLiteDatabase, mwebUtxosHeight: Int) {
        database.execSQL(
            """
            INSERT INTO `MwebState` (
                `id`,
                `blockHeaderHeight`,
                `mwebHeaderHeight`,
                `mwebUtxosHeight`,
                `lastSyncedAt`
            ) VALUES (0, ?, ?, ?, 10)
            """.trimIndent(),
            arrayOf(mwebUtxosHeight, mwebUtxosHeight, mwebUtxosHeight),
        )
    }

    private fun insertV3Rows(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO `MwebState` (
                `id`,
                `blockHeaderHeight`,
                `mwebHeaderHeight`,
                `mwebUtxosHeight`,
                `lastSyncedAt`
            ) VALUES (0, 500, 500, 400, 10)
            """.trimIndent()
        )
        database.insertOutgoingV3(
            uid = "legacy-peg-out",
            type = MwebTransactionType.Outgoing.name,
            kind = MwebTransactionKind.MwebToPublic.name,
            createdOutputIds = "[]",
            spentOutputIds = """["input-a"]""",
        )
        database.insertOutgoingV3(
            uid = "pending-with-change",
            type = MwebTransactionType.Outgoing.name,
            kind = MwebTransactionKind.MwebToPublic.name,
            createdOutputIds = """["change-output"]""",
            spentOutputIds = """["input-b"]""",
        )
        database.insertOutgoingV3(
            uid = "peg-in",
            type = MwebTransactionType.Incoming.name,
            kind = MwebTransactionKind.PublicToMweb.name,
            createdOutputIds = """["created-output"]""",
            spentOutputIds = "[]",
        )
    }

    private fun SupportSQLiteDatabase.insertOutgoingV3(
        uid: String,
        type: String,
        kind: String,
        createdOutputIds: String,
        spentOutputIds: String,
    ) {
        execSQL(
            """
            INSERT INTO `MwebOutgoingTransaction` (
                `uid`,
                `type`,
                `kind`,
                `amount`,
                `fee`,
                `destinationAddress`,
                `canonicalTransactionHash`,
                `createdOutputIds`,
                `spentOutputIds`,
                `timestamp`
            ) VALUES (?, ?, ?, 100, 1, NULL, ?, ?, ?, 1000)
            """.trimIndent(),
            arrayOf(uid, type, kind, "hash-$uid", createdOutputIds, spentOutputIds),
        )
    }

    private companion object {
        const val DB_NAME = "mweb-migration-test"
    }
}

package io.horizontalsystems.bitcoincore.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class DatabaseFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun deleteDatabaseFiles_databaseWithSidecars_deletesMainFileAndAllSidecars() {
        val dataDir = folder.newFolder().absolutePath
        val main = createFile(dataDir, DB_NAME)
        val journal = createFile(dataDir, "$DB_NAME-journal")
        val shm = createFile(dataDir, "$DB_NAME-shm")
        val wal = createFile(dataDir, "$DB_NAME-wal")
        val wipeCheck = createFile(dataDir, "$DB_NAME-wipecheck")
        val masterJournal = File(dataDir, "$DB_NAME-mj00000001").apply {
            mkdir()
            createFile(path, "leftover")
        }

        deleteDatabaseFiles(dataDir, DB_NAME)

        assertFalse(main.exists())
        assertFalse(journal.exists())
        assertFalse(shm.exists())
        assertFalse(wal.exists())
        assertFalse(wipeCheck.exists())
        assertFalse(masterJournal.exists())
    }

    @Test
    fun deleteDatabaseFiles_otherDatabaseInSameDir_leavesItUntouched() {
        val dataDir = folder.newFolder().absolutePath
        createFile(dataDir, DB_NAME)
        val neighbour = createFile(dataDir, "Bitcoin-MainNet-w2-Api-BIP84")
        val neighbourJournal = createFile(dataDir, "Bitcoin-MainNet-w2-Api-BIP84-journal")

        deleteDatabaseFiles(dataDir, DB_NAME)

        assertTrue(neighbour.exists())
        assertTrue(neighbourJournal.exists())
    }

    @Test
    fun deleteDatabaseFiles_missingDatabase_doesNotThrow() {
        deleteDatabaseFiles(folder.newFolder().absolutePath, DB_NAME)
    }

    @Test
    fun deleteDatabaseFiles_migrationLockHeld_doesNotDeleteDatabase() {
        val dataDir = folder.newFolder()
        val database = createFile(dataDir.path, DB_NAME)
        val failure = AtomicReference<Throwable?>()

        withDatabaseMigrationLock(dataDir) {
            val deleteThread = Thread {
                try {
                    deleteDatabaseFiles(dataDir.path, DB_NAME)
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            deleteThread.start()
            deleteThread.join()
        }

        assertTrue(failure.get() is DatabaseMigrationConflictException)
        assertTrue(database.exists())
    }

    private fun createFile(dir: String, name: String) = File(dir, name).apply { writeText("x") }

    companion object {
        private const val DB_NAME = "Bitcoin-MainNet-w1-Api-BIP84"
    }
}

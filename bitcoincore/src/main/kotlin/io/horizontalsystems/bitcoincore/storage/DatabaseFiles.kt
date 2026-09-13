package io.horizontalsystems.bitcoincore.storage

import java.io.File

// Mirrors SQLiteDatabase.deleteDatabase: the main file plus its -journal/-shm/-wal/-wipecheck
// siblings and any -mj* master-journal leftovers.
fun deleteDatabaseFiles(dataDir: String, dbName: String) {
    val directory = File(dataDir)
    withDatabaseMigrationLock(directory) {
        val file = File(directory, dbName)
        verifyNoPendingDatabaseMigration(file.path)
        sqliteDatabaseFiles(file).forEach(File::deleteRecursively)
    }
}

internal fun sqliteDatabaseFamily(file: File): List<File> = listOf(
    file,
    File("${file.path}-journal"),
    File("${file.path}-shm"),
    File("${file.path}-wal"),
    File("${file.path}-wipecheck"),
)

internal fun sqliteDatabaseFiles(file: File): List<File> = buildList {
    addAll(sqliteDatabaseFamily(file))
    file.parentFile
        ?.listFiles { candidate -> candidate.name.startsWith("${file.name}-mj") }
        ?.let(::addAll)
}

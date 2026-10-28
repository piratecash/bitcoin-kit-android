package io.horizontalsystems.sqlcipher.room

import java.io.File

// Mirrors SQLiteDatabase.deleteDatabase: the main file plus its -journal/-shm/-wal/-wipecheck
// siblings and any -mj* master-journal leftovers.
internal fun deleteDatabaseFiles(dataDir: String, dbName: String, fileNames: MigrationFileNames) {
    val directory = File(dataDir)
    withDatabaseMigrationLock(directory, fileNames) {
        val file = File(directory, dbName)
        verifyNoPendingDatabaseMigration(file.path, fileNames)
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

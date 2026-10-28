package io.horizontalsystems.sqlcipher.room

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.IOException

private val sqlCipherLoaded by lazy { System.loadLibrary("sqlcipher") }

internal fun ensureSqlCipherLoaded() {
    sqlCipherLoaded
}

internal fun exportPlaintextDatabase(source: File, target: File, databaseKey: ByteArray) {
    require(isPlaintextDatabase(source)) { "Migration source is not a plaintext SQLite database: ${source.path}" }
    require(!target.exists()) { "Migration target already exists: ${target.path}" }
    ensureSqlCipherLoaded()
    val keyBytes = databaseKeyLiteral(databaseKey)
    try {
        SQLiteDatabase.openDatabase(
            source.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
        ).use { database ->
            database.rawExecSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            val userVersion = database.rawQuery("PRAGMA user_version", emptyArray<String>()).use { cursor ->
                check(cursor.moveToFirst()) { "SQLCipher returned no user_version" }
                cursor.getInt(0)
            }
            // Bound as byte[]: the operation log redacts blobs but keeps Strings; ATTACH reads both as raw bytes.
            database.execSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?",
                arrayOf(target.absolutePath, keyBytes),
            )
            database.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            database.rawExecSQL("PRAGMA encrypted.user_version=$userVersion")
            database.rawExecSQL("DETACH DATABASE encrypted")
        }
    } finally {
        keyBytes.fill(0)
    }
    verifyEncryptedDatabaseFile(target, databaseKey)
}

internal fun verifyEncryptedDatabaseFile(file: File, databaseKey: ByteArray) {
    ensureSqlCipherLoaded()
    val literal = databaseKeyLiteral(databaseKey)
    try {
        SQLiteDatabase.openDatabase(file.absolutePath, literal, null, SQLiteDatabase.OPEN_READONLY, null).use { database ->
            database.rawQuery("PRAGMA integrity_check", emptyArray<String>()).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "SQLCipher integrity check failed" }
            }
        }
    } finally {
        literal.fill(0)
    }
}

internal fun platformAtomicMove(source: File, target: File, replace: Boolean) {
    if (!replace && target.exists()) throw IOException("Target already exists: ${target.path}")
    try {
        Os.rename(source.path, target.path)
    } catch (error: ErrnoException) {
        throw IOException("Unable to move ${source.path} to ${target.path}", error)
    }
}

internal fun platformForceDirectory(directory: File) {
    try {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    } catch (error: ErrnoException) {
        throw IOException("Unable to sync ${directory.path}", error)
    }
}

internal fun verifyEncryptedDatabaseAccess(path: String, databaseKey: ByteArray, fileNames: MigrationFileNames) {
    ensureSqlCipherLoaded()
    val key = existingEncryptedDatabaseKeyOrNull(path, databaseKey, fileNames) ?: return
    try {
        val literal = databaseKeyLiteral(key)
        try {
            SQLiteDatabase.openDatabase(path, literal, null, SQLiteDatabase.OPEN_READONLY, null).use { database ->
                database.rawQuery("SELECT count(*) FROM sqlite_schema", emptyArray<String>()).use { cursor ->
                    cursor.moveToFirst()
                }
            }
        } catch (error: RuntimeException) {
            throw DatabaseKeyMismatchException(path, error)
        } finally {
            literal.fill(0)
        }
    } finally {
        key.fill(0)
    }
}

internal fun <T : RoomDatabase> applySqlCipher(builder: RoomDatabase.Builder<T>, databaseKey: ByteArray): RoomDatabase.Builder<T> =
    builder.openHelperFactory(SupportOpenHelperFactory(databaseKeyLiteral(databaseKey)))

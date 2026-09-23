package io.horizontalsystems.bitcoincore.storage

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

private val sqlCipherLoaded by lazy { System.loadLibrary("sqlcipher") }

internal fun ensureSqlCipherLoaded() {
    sqlCipherLoaded
}

internal fun exportPlaintextDatabase(source: File, target: File, databaseKey: ByteArray) {
    require(isPlaintextDatabase(source)) { "Migration source is not a plaintext SQLite database: ${source.path}" }
    require(!target.exists()) { "Migration target already exists: ${target.path}" }
    ensureSqlCipherLoaded()
    val keyBytes = databaseKeyLiteral(databaseKey)
    val keyLiteral = try {
        String(keyBytes, StandardCharsets.US_ASCII)
    } finally {
        keyBytes.fill(0)
    }
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
        database.execSQL(
            "ATTACH DATABASE ? AS encrypted KEY ?",
            arrayOf(target.absolutePath, keyLiteral),
        )
        database.rawExecSQL("SELECT sqlcipher_export('encrypted')")
        database.rawExecSQL("PRAGMA encrypted.user_version=$userVersion")
        database.rawExecSQL("DETACH DATABASE encrypted")
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

package io.horizontalsystems.bitcoincore.storage

import java.io.File

abstract class DatabaseEncryptionException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class DatabaseMigrationRequiredException(val databasePath: String) : DatabaseEncryptionException(
    "Database must be migrated before it can be opened with encryption: $databasePath",
)

class DatabaseKeyRequiredException(val databasePath: String) : DatabaseEncryptionException(
    "Encrypted database requires a key: $databasePath",
)

class DatabaseKeyMismatchException(val databasePath: String, cause: Throwable) : DatabaseEncryptionException(
    "Database key is invalid for: $databasePath",
    cause,
)

internal const val DATABASE_KEY_SIZE = 32

internal fun validatedDatabaseKey(rawKey: ByteArray): ByteArray {
    val key = rawKey.copyOf()
    if (key.size == DATABASE_KEY_SIZE) return key
    key.fill(0)
    throw IllegalArgumentException("Database key must contain exactly $DATABASE_KEY_SIZE bytes")
}

internal fun existingEncryptedDatabaseKeyOrNull(path: String, rawKey: ByteArray): ByteArray? {
    verifyNoPendingDatabaseMigration(path)
    val key = validatedDatabaseKey(rawKey)
    try {
        val file = File(path)
        if (!file.exists()) {
            key.fill(0)
            return null
        }
        if (isPlaintextDatabase(file)) throw DatabaseMigrationRequiredException(path)
        return key
    } catch (error: Throwable) {
        key.fill(0)
        throw error
    }
}

@PublishedApi
internal fun verifyPlaintextDatabaseAccess(path: String) {
    verifyNoPendingDatabaseMigration(path)
    val file = File(path)
    if (file.exists() && !isPlaintextDatabase(file)) throw DatabaseKeyRequiredException(path)
}

@PublishedApi
internal fun databaseKeyLiteral(rawKey: ByteArray): ByteArray {
    val key = validatedDatabaseKey(rawKey)
    val hex = "0123456789abcdef".encodeToByteArray()
    return ByteArray(67).also { literal ->
        literal[0] = 'x'.code.toByte()
        literal[1] = '\''.code.toByte()
        key.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            literal[2 + index * 2] = hex[value ushr 4]
            literal[3 + index * 2] = hex[value and 0x0f]
        }
        literal[66] = '\''.code.toByte()
        key.fill(0)
    }
}

internal fun isPlaintextDatabase(file: File): Boolean {
    if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
    val header = file.inputStream().use { input -> ByteArray(SQLITE_HEADER.size).also { input.read(it) } }
    return header.contentEquals(SQLITE_HEADER)
}

private val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()

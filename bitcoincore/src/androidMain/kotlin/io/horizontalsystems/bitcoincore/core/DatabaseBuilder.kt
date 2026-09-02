package io.horizontalsystems.bitcoincore.core

import androidx.room.Room
import androidx.room.RoomDatabase
import io.horizontalsystems.bitcoincore.storage.DatabaseKeyMismatchException
import io.horizontalsystems.bitcoincore.storage.databaseKeyLiteral
import io.horizontalsystems.bitcoincore.storage.ensureSqlCipherLoaded
import io.horizontalsystems.bitcoincore.storage.existingEncryptedDatabaseKeyOrNull
import io.horizontalsystems.bitcoincore.storage.verifyPlaintextDatabaseAccess
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

inline fun <reified T : RoomDatabase> databaseBuilder(
    path: String,
    allowMainThreadQueries: Boolean = false,
): RoomDatabase.Builder<T> =
    Room.databaseBuilder(appContext, T::class.java, path).also { verifyPlaintextDatabaseAccess(path) }
        .apply { if (allowMainThreadQueries) allowMainThreadQueries() }

inline fun <reified T : RoomDatabase> databaseBuilder(
    path: String,
    databaseKey: ByteArray?,
    allowMainThreadQueries: Boolean = false,
): RoomDatabase.Builder<T> {
    if (databaseKey == null) return databaseBuilder(path, allowMainThreadQueries)
    verifyEncryptedDatabaseAccess(path, databaseKey)
    return Room.databaseBuilder(appContext, T::class.java, path)
        .openHelperFactory(SupportOpenHelperFactory(databaseKeyLiteral(databaseKey)))
        .apply { if (allowMainThreadQueries) allowMainThreadQueries() }
}

@PublishedApi
internal fun verifyEncryptedDatabaseAccess(path: String, databaseKey: ByteArray) {
    ensureSqlCipherLoaded()
    val key = existingEncryptedDatabaseKeyOrNull(path, databaseKey) ?: return
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

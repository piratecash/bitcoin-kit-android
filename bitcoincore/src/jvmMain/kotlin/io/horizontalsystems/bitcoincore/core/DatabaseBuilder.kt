package io.horizontalsystems.bitcoincore.core

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.horizontalsystems.bitcoincore.storage.DatabaseKeyMismatchException
import io.horizontalsystems.bitcoincore.storage.existingEncryptedDatabaseKeyOrNull
import io.horizontalsystems.bitcoincore.storage.verifyPlaintextDatabaseAccess
import io.horizontalsystems.sqlcipher.SqlCipherDriver
import kotlinx.coroutines.Dispatchers

// `allowMainThreadQueries` is ignored: there is no main-thread check off Android.
inline fun <reified T : RoomDatabase> databaseBuilder(
    path: String,
    allowMainThreadQueries: Boolean = false,
): RoomDatabase.Builder<T> =
    Room.databaseBuilder<T>(path).also { verifyPlaintextDatabaseAccess(path) }
        .setDriver(BundledSQLiteDriver())
        // A blocking DAO nested in a transaction must reach Room's `useConnection` undispatched, before
        // its first suspension, so Room recovers the transaction's connection from its thread local.
        .setQueryCoroutineContext(Dispatchers.Unconfined)

inline fun <reified T : RoomDatabase> databaseBuilder(
    path: String,
    databaseKey: ByteArray?,
    allowMainThreadQueries: Boolean = false,
): RoomDatabase.Builder<T> {
    if (databaseKey == null) return databaseBuilder(path, allowMainThreadQueries)
    verifyEncryptedDatabaseAccess(path, databaseKey)
    return Room.databaseBuilder<T>(path)
        .setDriver(SqlCipherDriver(databaseKey))
        .setQueryCoroutineContext(Dispatchers.Unconfined)
}

@PublishedApi
internal fun verifyEncryptedDatabaseAccess(path: String, databaseKey: ByteArray) {
    val key = existingEncryptedDatabaseKeyOrNull(path, databaseKey) ?: return
    try {
        SqlCipherDriver(key).use { driver ->
            driver.open(path).use { connection ->
                connection.prepare("SELECT count(*) FROM sqlite_schema").use { statement -> statement.step() }
            }
        }
    } catch (error: RuntimeException) {
        throw DatabaseKeyMismatchException(path, error)
    } finally {
        key.fill(0)
    }
}

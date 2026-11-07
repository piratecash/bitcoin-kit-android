package io.horizontalsystems.bitcoincore.storage

import androidx.room.RoomDatabase
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.databasenaming.DatabaseNameResolver
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Two Room instances on one file lock each other out (SQLITE_BUSY); every kit database goes through here. */
object SharedRoomDatabases {
    private const val SEPARATOR = "|"
    private val instances = ConcurrentHashMap<String, RoomDatabase>()

    /** The instance is shared: consumers must never `close()` it, a closed cached instance cannot be detected. */
    @Suppress("UNCHECKED_CAST")
    fun <T : RoomDatabase> getOrBuild(
        path: String,
        databaseKey: ByteArray?,
        nameResolver: DatabaseNameResolver,
        build: () -> T,
    ): T {
        // A cache hit skips the builder, yet the file may have entered or finished a migration since.
        if (databaseKey == null) bitcoinKitDatabases(nameResolver).verifyPlaintextDatabaseAccess(path)
        return instances.computeIfAbsent(canonicalPath(path) + SEPARATOR + fingerprint(databaseKey)) { build() } as T
    }

    /** Drops the cached instances of [path] without closing them: a disposed kit may still write late. */
    fun evict(path: String) {
        val prefix = canonicalPath(path) + SEPARATOR
        instances.keys.removeIf { it.startsWith(prefix) }
    }

    private fun canonicalPath(path: String): String = File(path).canonicalPath

    private fun fingerprint(databaseKey: ByteArray?): String =
        databaseKey?.let { MessageDigest.getInstance("SHA-256").digest(it).toHexString() } ?: "plain"
}

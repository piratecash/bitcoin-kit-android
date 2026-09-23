package io.horizontalsystems.sqlcipher

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.HexFormat

class SqlCipherDriver(key: ByteArray) : SQLiteDriver, AutoCloseable {
    private val key = validatedKeyCopy(key)
    private var closed = false

    override fun open(fileName: String): SQLiteConnection = synchronized(key) {
        check(!closed) { "SQLCipher driver is closed" }
        SqlCipherConnection(SqlCipherNative.open(fileName.utf8Path(), key))
    }

    override fun close() = synchronized(key) {
        if (!closed) {
            key.fill(0)
            closed = true
        }
    }
}

object SqlCipherMigration {
    fun exportPlaintext(sourcePath: String, targetPath: String, key: ByteArray) {
        require(File(sourcePath).isFile) { "Plaintext database does not exist: $sourcePath" }
        require(!File(targetPath).exists()) { "Migration target already exists: $targetPath" }
        val keyCopy = validatedKeyCopy(key)
        try {
            SqlCipherNative.exportPlaintext(sourcePath.utf8Path(), targetPath.utf8Path(), keyCopy)
        } finally {
            keyCopy.fill(0)
        }
    }
}

object SqlCipherFileSystem {
    fun atomicMove(sourcePath: String, targetPath: String, replace: Boolean) {
        SqlCipherNative.atomicMove(sourcePath.utf8Path(), targetPath.utf8Path(), replace)
    }

    fun forceDirectory(path: String) {
        SqlCipherNative.forceDirectory(path.utf8Path())
    }
}

private fun validatedKeyCopy(rawKey: ByteArray): ByteArray {
    val key = rawKey.copyOf()
    if (key.size == KEY_SIZE) return key
    key.fill(0)
    throw IllegalArgumentException("SQLCipher key must contain exactly $KEY_SIZE bytes")
}

private fun String.utf8Path(): ByteArray = toByteArray(StandardCharsets.UTF_8)

private class SqlCipherConnection(private var handle: Long) : SQLiteConnection {
    @Synchronized
    override fun inTransaction(): Boolean = SqlCipherNative.inTransaction(requireHandle())

    @Synchronized
    override fun prepare(sql: String): SQLiteStatement =
        SqlCipherStatement(SqlCipherNative.prepare(requireHandle(), sql))

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            SqlCipherNative.closeConnection(handle)
            handle = 0L
        }
    }

    private fun requireHandle(): Long {
        check(handle != 0L) { "SQLCipher connection is closed" }
        return handle
    }
}

private class SqlCipherStatement(private var handle: Long) : SQLiteStatement {
    @Synchronized
    override fun bindBlob(index: Int, value: ByteArray) = SqlCipherNative.bindBlob(requireHandle(), index, value)

    @Synchronized
    override fun bindDouble(index: Int, value: Double) = SqlCipherNative.bindDouble(requireHandle(), index, value)

    @Synchronized
    override fun bindLong(index: Int, value: Long) = SqlCipherNative.bindLong(requireHandle(), index, value)

    @Synchronized
    override fun bindText(index: Int, value: String) = SqlCipherNative.bindText(requireHandle(), index, value)

    @Synchronized
    override fun bindNull(index: Int) = SqlCipherNative.bindNull(requireHandle(), index)

    @Synchronized
    override fun getBlob(index: Int): ByteArray = SqlCipherNative.getBlob(requireHandle(), index)

    @Synchronized
    override fun getDouble(index: Int): Double = SqlCipherNative.getDouble(requireHandle(), index)

    @Synchronized
    override fun getLong(index: Int): Long = SqlCipherNative.getLong(requireHandle(), index)

    @Synchronized
    override fun getText(index: Int): String = SqlCipherNative.getText(requireHandle(), index)

    @Synchronized
    override fun isNull(index: Int): Boolean = SqlCipherNative.isNull(requireHandle(), index)

    @Synchronized
    override fun getColumnCount(): Int = SqlCipherNative.getColumnCount(requireHandle())

    @Synchronized
    override fun getColumnName(index: Int): String = SqlCipherNative.getColumnName(requireHandle(), index)

    @Synchronized
    override fun getColumnType(index: Int): Int = SqlCipherNative.getColumnType(requireHandle(), index)

    @Synchronized
    override fun step(): Boolean = SqlCipherNative.step(requireHandle())

    @Synchronized
    override fun reset() = SqlCipherNative.reset(requireHandle())

    @Synchronized
    override fun clearBindings() = SqlCipherNative.clearBindings(requireHandle())

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            SqlCipherNative.closeStatement(handle)
            handle = 0L
        }
    }

    private fun requireHandle(): Long {
        check(handle != 0L) { "SQLCipher statement is closed" }
        return handle
    }
}

private object SqlCipherNative {
    init {
        SqlCipherNativeLibrary.load()
    }

    external fun open(path: ByteArray, key: ByteArray): Long
    external fun exportPlaintext(sourcePath: ByteArray, targetPath: ByteArray, key: ByteArray)
    external fun atomicMove(sourcePath: ByteArray, targetPath: ByteArray, replace: Boolean)
    external fun forceDirectory(path: ByteArray)
    external fun closeConnection(handle: Long)
    external fun inTransaction(handle: Long): Boolean
    external fun prepare(connectionHandle: Long, sql: String): Long
    external fun bindBlob(handle: Long, index: Int, value: ByteArray)
    external fun bindDouble(handle: Long, index: Int, value: Double)
    external fun bindLong(handle: Long, index: Int, value: Long)
    external fun bindText(handle: Long, index: Int, value: String)
    external fun bindNull(handle: Long, index: Int)
    external fun getBlob(handle: Long, index: Int): ByteArray
    external fun getDouble(handle: Long, index: Int): Double
    external fun getLong(handle: Long, index: Int): Long
    external fun getText(handle: Long, index: Int): String
    external fun isNull(handle: Long, index: Int): Boolean
    external fun getColumnCount(handle: Long): Int
    external fun getColumnName(handle: Long, index: Int): String
    external fun getColumnType(handle: Long, index: Int): Int
    external fun step(handle: Long): Boolean
    external fun reset(handle: Long)
    external fun clearBindings(handle: Long)
    external fun closeStatement(handle: Long)
}

private object SqlCipherNativeLibrary {
    private const val NAME = "sqlcipher_jni"

    fun load() {
        val fileName = System.mapLibraryName(NAME)
        val resourcePath = "/natives/${platformTag()}/$fileName"
        val bytes = resource(resourcePath).use(InputStream::readBytes)
        verifyChecksum(resourcePath, bytes)
        System.load(extract(fileName, bytes).toString())
    }

    private fun verifyChecksum(resourcePath: String, bytes: ByteArray) {
        val expected = resource("$resourcePath.sha256").bufferedReader().use { it.readText().trim() }
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "invalid SQLCipher native checksum" }
        val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        check(MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())) {
            "SQLCipher native checksum mismatch"
        }
    }

    private fun extract(fileName: String, bytes: ByteArray): Path =
        Files.createTempDirectory("bitcoin-kit-sqlcipher-").also(::restrictDirectory).resolve(fileName).also {
            Files.write(it, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            it.toFile().deleteOnExit()
            it.parent.toFile().deleteOnExit()
        }

    private fun restrictDirectory(path: Path) {
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows temp directories inherit the current user's ACL.
        }
    }

    private fun resource(path: String): InputStream =
        checkNotNull(SqlCipherNativeLibrary::class.java.getResourceAsStream(path)) {
            "SQLCipher native is missing: $path"
        }

    private fun platformTag(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        return when {
            (os.contains("mac") || os.contains("darwin")) && arch in setOf("aarch64", "arm64") -> "macos-arm64"
            os.contains("linux") && arch in setOf("x86_64", "amd64") -> "linux-x64"
            os.contains("win") && arch in setOf("x86_64", "amd64") -> "windows-x64"
            else -> throw UnsupportedOperationException("SQLCipher is not supported on $os/$arch")
        }
    }
}

private const val KEY_SIZE = 32

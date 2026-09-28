package io.horizontalsystems.sqlcipher

import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SqlCipherDriverTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun open_rawKey_encryptsDatabaseAndReadsAllTypes() {
        val path = Files.createTempDirectory("sqlcipher-driver-😀").resolve("wallet.db")
        SqlCipherDriver(key).use { driver ->
            driver.open(path.toString()).use { connection ->
                connection.execSQL("CREATE TABLE sample(id INTEGER PRIMARY KEY, text_value TEXT, real_value REAL, blob_value BLOB)")
                connection.prepare("INSERT INTO sample VALUES(?, ?, ?, ?)").use { statement ->
                    statement.bindLong(1, 7)
                    statement.bindText(2, "кошелёк")
                    statement.bindDouble(3, 1.25)
                    statement.bindBlob(4, byteArrayOf(1, 2, 3))
                    assertFalse(statement.step())
                }
                connection.prepare("SELECT id, text_value, real_value, blob_value FROM sample").use { statement ->
                    assertTrue(statement.step())
                    assertEquals(7, statement.getInt(0))
                    assertEquals("кошелёк", statement.getText(1))
                    assertEquals(1.25, statement.getDouble(2), 0.0)
                    assertArrayEquals(byteArrayOf(1, 2, 3), statement.getBlob(3))
                    assertFalse(statement.step())
                }
            }
        }

        val header = Files.readAllBytes(path).copyOfRange(0, 16)
        assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray()))
    }

    @Test
    fun open_wrongKey_rejectsEncryptedDatabase() {
        val path = Files.createTempDirectory("sqlcipher-driver").resolve("wallet.db")
        SqlCipherDriver(key).use { driver ->
            driver.open(path.toString()).use { it.execSQL("CREATE TABLE sample(id INTEGER)") }
        }

        SqlCipherDriver(ByteArray(32) { 1 }).use { driver ->
            assertThrows(RuntimeException::class.java) { driver.open(path.toString()) }
        }
    }

    @Test
    fun constructor_non32ByteKey_rejectsKey() {
        assertThrows(IllegalArgumentException::class.java) { SqlCipherDriver(ByteArray(31)) }
    }

    @Test
    fun exportPlaintext_existingDatabase_preservesDataAndUserVersion() {
        val directory = Files.createTempDirectory("sqlcipher-migration-😀")
        val temporarySource = Files.createTempFile("sqlcipher-plaintext", ".db")
        val source = directory.resolve("plain.db")
        val target = directory.resolve("encrypted.db")
        BundledSQLiteDriver().open(temporarySource.toString()).use { connection ->
            connection.execSQL("CREATE TABLE sample(value TEXT NOT NULL)")
            connection.execSQL("INSERT INTO sample VALUES('preserved')")
            connection.execSQL("PRAGMA user_version=17")
        }
        Files.move(temporarySource, source)

        SqlCipherMigration.exportPlaintext(source.toString(), target.toString(), key)

        assertTrue(Files.readAllBytes(source).copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray()))
        SqlCipherDriver(key).use { driver ->
            driver.open(target.toString()).use { connection ->
                connection.prepare("SELECT value FROM sample").use { statement ->
                    assertTrue(statement.step())
                    assertEquals("preserved", statement.getText(0))
                }
                connection.prepare("PRAGMA user_version").use { statement ->
                    assertTrue(statement.step())
                    assertEquals(17, statement.getInt(0))
                }
            }
        }
    }
}

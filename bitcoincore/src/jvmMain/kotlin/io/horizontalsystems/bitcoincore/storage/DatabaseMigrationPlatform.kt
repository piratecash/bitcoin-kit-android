package io.horizontalsystems.bitcoincore.storage

import io.horizontalsystems.sqlcipher.SqlCipherDriver
import io.horizontalsystems.sqlcipher.SqlCipherFileSystem
import io.horizontalsystems.sqlcipher.SqlCipherMigration
import java.io.File

internal fun exportPlaintextDatabase(source: File, target: File, databaseKey: ByteArray) {
    require(isPlaintextDatabase(source)) { "Migration source is not a plaintext SQLite database: ${source.path}" }
    SqlCipherMigration.exportPlaintext(source.absolutePath, target.absolutePath, databaseKey)
    verifyEncryptedDatabaseFile(target, databaseKey)
}

internal fun verifyEncryptedDatabaseFile(file: File, databaseKey: ByteArray) {
    SqlCipherDriver(databaseKey).use { driver ->
        driver.open(file.absolutePath).use { connection ->
            connection.prepare("PRAGMA integrity_check").use { statement ->
                check(statement.step() && statement.getText(0) == "ok") { "SQLCipher integrity check failed" }
            }
        }
    }
}

internal fun platformAtomicMove(source: File, target: File, replace: Boolean) {
    SqlCipherFileSystem.atomicMove(source.absolutePath, target.absolutePath, replace)
}

internal fun platformForceDirectory(directory: File) {
    SqlCipherFileSystem.forceDirectory(directory.absolutePath)
}

package io.horizontalsystems.sqlcipher

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SqlCipherNativeLibraryTest {
    @Test
    fun load_nativeDirWithoutLibrary_failsNamingThePath() {
        val nativeDir = Files.createTempDirectory("sqlcipher-native-dir")
        val expected = nativeDir.resolve(System.mapLibraryName("sqlcipher_jni")).toString()

        val error = assertThrows(IllegalArgumentException::class.java) {
            SqlCipherNativeLibrary.load(nativeDir.toString())
        }

        assertTrue(error.message.orEmpty().contains(expected))
    }
}

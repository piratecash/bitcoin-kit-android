package io.horizontalsystems.dashlibnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DashjBlsLibraryTest {
    @Test
    fun load_nativeDirWithoutLibrary_failsNamingThePathWithoutExtracting() {
        val nativeDir = Files.createTempDirectory("dashjbls-native-dir")
        val expected = nativeDir.resolve(System.mapLibraryName("dashjbls")).toString()
        val extractedBefore = extractedCopies()

        val error = assertThrows(IllegalArgumentException::class.java) {
            DashjBlsLibrary.load(nativeDir.toString())
        }

        assertTrue(error.message.orEmpty().contains(expected))
        assertEquals(extractedBefore, extractedCopies())
    }

    private fun extractedCopies(): Set<String> =
        File(System.getProperty("java.io.tmpdir")).list { _, name -> name.startsWith("dashjbls") }.orEmpty().toSet()
}

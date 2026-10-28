package io.horizontalsystems.dashlibnative

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Loads the packaged `dashjbls` native on desktop JVMs. Desktop applications must touch
 * [available] on startup, before constructing any kit; Android loads the native itself.
 */
object DashjBlsLibrary {
    private const val NAME = "dashjbls"
    private val logger = Logger.getLogger("DashjBlsLibrary")

    val available: Boolean by lazy { loadOnce() }

    private fun loadOnce(): Boolean = try {
        val fileName = System.mapLibraryName(NAME)
        val packaged = DashjBlsLibrary::class.java
            .getResourceAsStream("/natives/${osTag()}-${archTag()}/$fileName")
        if (packaged != null) {
            // Deliberately not a fallback: a packaged resource that fails to load must not be
            // replaced by a stray library from java.library.path.
            System.load(extractToTempFile(packaged, fileName))
        } else {
            System.loadLibrary(NAME)
        }
        true
    } catch (e: LinkageError) {
        failed(e)
    } catch (e: Exception) {
        failed(e)
    }

    private fun extractToTempFile(source: InputStream, fileName: String): String {
        val file = Files.createTempFile(NAME, fileName.substring(fileName.lastIndexOf('.')))
        source.use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
        file.toFile().deleteOnExit()
        return file.toAbsolutePath().toString()
    }

    private fun failed(e: Throwable): Boolean {
        logger.log(Level.SEVERE, "native $NAME unavailable: BLS signatures will not be verified", e)
        return false
    }

    private fun osTag(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("win") -> "windows"
            os.contains("linux") -> "linux"
            else -> os
        }
    }

    private fun archTag(): String = when (val arch = System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "x86_64", "amd64" -> "x64"
        else -> arch
    }
}

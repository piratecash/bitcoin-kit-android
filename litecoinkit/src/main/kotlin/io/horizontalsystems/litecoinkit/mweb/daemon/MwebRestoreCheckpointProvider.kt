package io.horizontalsystems.litecoinkit.mweb.daemon

import io.horizontalsystems.litecoinkit.LitecoinKit
import java.nio.charset.StandardCharsets

internal object MwebRestoreCheckpointProvider {
    fun encodedCheckpoint(networkType: LitecoinKit.NetworkType, restoreHeight: Int): String? {
        val fileName = when (networkType) {
            LitecoinKit.NetworkType.MainNet -> "MainNetLitecoin-mweb.checkpoint"
            LitecoinKit.NetworkType.TestNet -> return null
        }

        return javaClass.classLoader
            ?.getResourceAsStream(fileName)
            ?.bufferedReader(StandardCharsets.US_ASCII)
            ?.useLines { lines -> encodedCheckpoint(lines, restoreHeight) }
    }

    internal fun encodedCheckpoint(lines: Sequence<String>, restoreHeight: Int): String? {
        return lines
            .mapNotNull(::parse)
            .filter { checkpoint -> checkpoint.height <= restoreHeight }
            .maxByOrNull { checkpoint -> checkpoint.height }
            ?.encoded
    }

    private fun parse(line: String): MwebRestoreCheckpoint? {
        val encoded = line.trim()
        if (encoded.isEmpty() || encoded.startsWith("#")) return null

        val parts = encoded.split("|")
        check(parts.size == FIELD_COUNT) {
            "Invalid MWEB checkpoint field count"
        }
        check(parts[VERSION_INDEX] == CHECKPOINT_VERSION) {
            "Unsupported MWEB checkpoint version"
        }

        val height = parts[HEIGHT_INDEX].toIntOrNull()
            ?: error("Invalid MWEB checkpoint height")

        return MwebRestoreCheckpoint(height, encoded)
    }

    private data class MwebRestoreCheckpoint(
        val height: Int,
        val encoded: String,
    )

    private const val CHECKPOINT_VERSION = "mweb-checkpoint-v1"
    private const val FIELD_COUNT = 6
    private const val VERSION_INDEX = 0
    private const val HEIGHT_INDEX = 1
}

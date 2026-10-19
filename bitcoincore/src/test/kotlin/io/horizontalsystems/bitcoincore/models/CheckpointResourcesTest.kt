package io.horizontalsystems.bitcoincore.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the shipped mainnet checkpoints against a malformed or misaligned bump. Heights are read
 * straight from the wire bytes, independently of [Checkpoint]'s own parser.
 */
class CheckpointResourcesTest {

    @Test
    fun `mainnet checkpoints - every row is a well formed header record with descending heights`() {
        CHECKPOINTS.forEach { (chain, path) ->
            val heights = heightsOf(path)

            assertTrue("$chain has no checkpoint rows", heights.isNotEmpty())
            assertEquals("$chain rows are not strictly descending", heights.sortedDescending(), heights)
            assertEquals("$chain has duplicate heights", heights.distinct().size, heights.size)
        }
    }

    @Test
    fun `litecoin checkpoint - sits on a retarget boundary and carries its companion`() {
        // Litecoin's legacy validator steps back heightInterval + 1, so validating the first
        // boundary above the checkpoint needs the block before it as well.
        val heights = heightsOf(CHECKPOINTS.getValue("LTC"))

        assertEquals(0, heights.first() % RETARGET_INTERVAL)
        assertTrue("companion block is missing", heights.contains(heights.first() - 1))
    }

    private fun heightsOf(path: String): List<Int> {
        val file = File(path)
        assertTrue("checkpoint file not found: ${file.absolutePath}", file.isFile)

        return file.readLines().filter { it.isNotBlank() }.map { line ->
            assertEquals("unexpected record length in $path", RECORD_HEX_LENGTH, line.trim().length)
            heightAt(line.trim())
        }
    }

    /** Height is a little-endian int at byte offset 80, right after the 80-byte header. */
    private fun heightAt(record: String): Int {
        val bytes = (0 until 4).map { record.substring(160 + it * 2, 162 + it * 2).toInt(16) }
        return bytes[0] or (bytes[1] shl 8) or (bytes[2] shl 16) or (bytes[3] shl 24)
    }

    private companion object {
        const val RECORD_HEX_LENGTH = 232
        const val RETARGET_INTERVAL = 2016

        val CHECKPOINTS = mapOf(
            "BTC" to "../bitcoinkit/src/main/resources/MainNet.checkpoint",
            "BCH" to "../bitcoincashkit/src/main/resources/MainNetBitcoinCash.checkpoint",
            "LTC" to "../litecoinkit/src/main/resources/MainNetLitecoin.checkpoint",
            "DASH" to "../dashkit/src/main/resources/MainNetDash.checkpoint",
            "ECASH" to "../ecashkit/src/main/resources/MainNetECash.checkpoint"
        )
    }
}

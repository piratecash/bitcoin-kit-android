package io.horizontalsystems.litecoinkit.mweb.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

class MwebdAndroidDaemonClientTest {
    @Test
    fun toMwebdExclusiveToIndex_inclusiveRangeEnd_convertsToExclusiveEnd() {
        listOf(
            0 to 1L,
            1 to 2L,
            100 to 101L,
        ).forEach { (inclusiveEnd, exclusiveEnd) ->
            assertEquals(exclusiveEnd, inclusiveEnd.toMwebdExclusiveToIndex())
        }
    }

    @Test
    fun isMwebInitMarker_emptyNativeUtxo_returnsTrue() {
        assertEquals(
            true,
            isMwebInitMarker(
                outputId = "",
                address = "",
                value = 0,
                height = 0,
                blockTime = 0,
            )
        )
    }

    @Test
    fun isMwebInitMarker_unconfirmedRealUtxo_returnsFalse() {
        assertEquals(
            false,
            isMwebInitMarker(
                outputId = "output-id",
                address = "ltcmweb-address",
                value = 1,
                height = 0,
                blockTime = 0,
            )
        )
    }

    @Test
    fun isMwebInitMarker_partiallyEmptyUtxo_returnsFalse() {
        val partialUtxos = listOf(
            MarkerFields(outputId = "output-id"),
            MarkerFields(address = "ltcmweb-address"),
            MarkerFields(value = 1),
            MarkerFields(height = 1),
            MarkerFields(blockTime = 1),
        )

        partialUtxos.forEach { fields ->
            assertEquals(
                false,
                isMwebInitMarker(
                    outputId = fields.outputId,
                    address = fields.address,
                    value = fields.value,
                    height = fields.height,
                    blockTime = fields.blockTime,
                )
            )
        }
    }

    private data class MarkerFields(
        val outputId: String = "",
        val address: String = "",
        val value: Long = 0,
        val height: Long = 0,
        val blockTime: Long = 0,
    )
}

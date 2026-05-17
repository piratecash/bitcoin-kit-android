package io.horizontalsystems.litecoinkit.mweb.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
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

    @Test
    fun utxos_onReplayCompleteMarker_callsOnReplayComplete() {
        var replayCompleteHeight: Int? = null
        val listener = listenerAdapter(onReplayComplete = { replayCompleteHeight = it })

        listener.onReplayComplete(123)

        assertEquals(123, replayCompleteHeight)
    }

    @Test
    fun utxos_onReplayCompleteMarker_doesNotCallOnUtxo() {
        var utxoCalled = false
        val listener = listenerAdapter(
            onUtxo = { utxoCalled = true },
            onReplayComplete = {},
        )

        listener.onReplayComplete(123)

        assertFalse(utxoCalled)
    }

    @Test
    fun utxos_onComplete_callsOnCompleteOnce() {
        var completeCount = 0
        val listener = listenerAdapter(onComplete = { completeCount += 1 })

        listener.onComplete()

        assertEquals(1, completeCount)
    }

    private fun listenerAdapter(
        onUtxo: () -> Unit = {},
        onReplayComplete: (Int) -> Unit = {},
        onComplete: () -> Unit = {},
    ): MwebUtxoListenerAdapter {
        return MwebUtxoListenerAdapter(
            aggregator = UtxoStreamAggregator(startedAt = 0),
            startedAt = 0,
            addressIndex = { 0 },
            handleUtxo = { onUtxo() },
            handleReplayComplete = onReplayComplete,
            handleComplete = onComplete,
            handleError = {},
        )
    }

    private data class MarkerFields(
        val outputId: String = "",
        val address: String = "",
        val value: Long = 0,
        val height: Long = 0,
        val blockTime: Long = 0,
    )
}

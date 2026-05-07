package io.horizontalsystems.litecoinkit.mweb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MwebSyncStateTest {
    @Test
    fun isSynced_heightsWithinTolerance_returnsTrue() {
        val state = MwebSyncState(
            blockHeaderHeight = 100,
            mwebHeaderHeight = 99,
            mwebUtxosHeight = 100,
        )

        assertTrue(state.isSynced(publicTipHeight = 100, tolerance = 1))
    }

    @Test
    fun isSynced_utxosBehindTolerance_returnsFalse() {
        val state = MwebSyncState(
            blockHeaderHeight = 100,
            mwebHeaderHeight = 100,
            mwebUtxosHeight = 98,
        )

        assertFalse(state.isSynced(publicTipHeight = 100, tolerance = 1))
    }
}

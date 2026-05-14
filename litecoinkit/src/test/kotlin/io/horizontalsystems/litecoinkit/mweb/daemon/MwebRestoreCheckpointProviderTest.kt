package io.horizontalsystems.litecoinkit.mweb.daemon

import io.horizontalsystems.litecoinkit.LitecoinKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MwebRestoreCheckpointProviderTest {
    @Test
    fun encodedCheckpoint_restoreHeightBetweenCheckpoints_returnsLatestBelowRestoreHeight() {
        val checkpoint = MwebRestoreCheckpointProvider.encodedCheckpoint(
            lines = sequenceOf(
                "# comment",
                "  ",
                "mweb-checkpoint-v1|2500000|a|b|c|1",
                "mweb-checkpoint-v1|2600000|a|b|c|1",
                "mweb-checkpoint-v1|2700000|a|b|c|1",
            ),
            restoreHeight = 2_650_000,
        )

        assertEquals("mweb-checkpoint-v1|2600000|a|b|c|1", checkpoint)
    }

    @Test
    fun encodedCheckpoint_restoreHeightEqualsCheckpointHeight_returnsExactCheckpoint() {
        val checkpoint = MwebRestoreCheckpointProvider.encodedCheckpoint(
            lines = sequenceOf(
                "mweb-checkpoint-v1|2500000|a|b|c|1",
                "mweb-checkpoint-v1|2600000|a|b|c|1",
            ),
            restoreHeight = 2_600_000,
        )

        assertEquals("mweb-checkpoint-v1|2600000|a|b|c|1", checkpoint)
    }

    @Test
    fun encodedCheckpoint_restoreHeightBeforeFirstCheckpoint_returnsNull() {
        val checkpoint = MwebRestoreCheckpointProvider.encodedCheckpoint(
            lines = sequenceOf("mweb-checkpoint-v1|2500000|a|b|c|1"),
            restoreHeight = 2_400_000,
        )

        assertNull(checkpoint)
    }

    @Test
    fun encodedCheckpoint_invalidCheckpointFieldCount_throws() {
        assertThrows(IllegalStateException::class.java) {
            MwebRestoreCheckpointProvider.encodedCheckpoint(
                lines = sequenceOf("bad|2500000|a|b|c|1"),
                restoreHeight = 2_500_000,
            )
        }
    }

    @Test
    fun encodedCheckpoint_unsupportedCheckpointVersion_throws() {
        assertThrows(IllegalStateException::class.java) {
            MwebRestoreCheckpointProvider.encodedCheckpoint(
                lines = sequenceOf("mweb-checkpoint-v2|2500000|a|b|c|1"),
                restoreHeight = 2_500_000,
            )
        }
    }

    @Test
    fun encodedCheckpoint_invalidCheckpointHeight_throws() {
        assertThrows(IllegalStateException::class.java) {
            MwebRestoreCheckpointProvider.encodedCheckpoint(
                lines = sequenceOf("mweb-checkpoint-v1|height|a|b|c|1"),
                restoreHeight = 2_500_000,
            )
        }
    }

    @Test
    fun encodedCheckpoint_mainnetResourceRestoreHeightAfterLatest_returnsLatestCheckpoint() {
        val checkpoint = MwebRestoreCheckpointProvider.encodedCheckpoint(
            LitecoinKit.NetworkType.MainNet,
            3_100_000,
        )

        assertTrue(checkpoint?.startsWith("mweb-checkpoint-v1|3050000|") == true)
    }

    @Test
    fun encodedCheckpoint_mainnetResourceActivationRestoreHeight_returnsNull() {
        val checkpoint = MwebRestoreCheckpointProvider.encodedCheckpoint(
            LitecoinKit.NetworkType.MainNet,
            2_257_920,
        )

        assertNull(checkpoint)
    }

    @Test
    fun encodedCheckpoint_testnet_returnsNull() {
        val checkpoint = MwebRestoreCheckpointProvider.encodedCheckpoint(
            LitecoinKit.NetworkType.TestNet,
            3_100_000,
        )

        assertNull(checkpoint)
    }
}

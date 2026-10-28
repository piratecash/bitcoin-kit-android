package io.horizontalsystems.bitcoincore.blocks.validators

import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.managers.BlockValidatorHelper
import io.horizontalsystems.bitcoincore.models.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LegacyDifficultyAdjustmentValidatorMissingAncestorTest {

    private val storage = mock<IStorage>()
    private val validator = LegacyDifficultyAdjustmentValidator(
        validatorHelper = BlockValidatorHelper(storage),
        heightInterval = HEIGHT_INTERVAL,
        targetTimespan = TARGET_TIMESPAN,
        maxTargetBits = MAX_TARGET_BITS
    )

    @Test
    fun `validate - checkpoint block missing - throws NoCheckpointBlock carrying the missing height`() {
        val block = block(height = 2016 * 1000)
        val missingHeight = block.height - HEIGHT_INTERVAL.toInt()
        whenever(storage.getBlockByHeightStalePrioritized(missingHeight)).thenReturn(null)

        val exception = assertThrows(BlockValidatorException.NoCheckpointBlock::class.java) {
            validator.validate(block, block(height = block.height - 1))
        }

        assertEquals(missingHeight, exception.height)
        verify(storage).getBlockByHeightStalePrioritized(missingHeight)
    }

    private fun block(height: Int) = Block().apply { this.height = height }

    private companion object {
        const val HEIGHT_INTERVAL = 2016L
        const val TARGET_TIMESPAN = 14 * 24 * 60 * 60L
        const val MAX_TARGET_BITS = 0x1d00ffffL
    }
}

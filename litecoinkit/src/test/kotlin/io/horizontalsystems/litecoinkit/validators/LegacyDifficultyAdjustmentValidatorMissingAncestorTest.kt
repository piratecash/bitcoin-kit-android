package io.horizontalsystems.litecoinkit.validators

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
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
    fun `validate - block before checkpoint missing - throws NoCheckpointBlock carrying the missing height`() {
        val block = block(height = 2016 * 1000)
        val missingHeight = block.height - (HEIGHT_INTERVAL.toInt() + 1)
        whenever(storage.getBlockByHeightStalePrioritized(missingHeight)).thenReturn(null)

        val exception = assertThrows(BlockValidatorException.NoCheckpointBlock::class.java) {
            validator.validate(block, block(height = block.height - 1))
        }

        assertEquals(missingHeight, exception.height)
        verify(storage).getBlockByHeightStalePrioritized(missingHeight)
    }

    @Test
    fun `validate - observed stuck wallet at height 3106656 - reports missing height 3104639`() {
        val block = block(height = 3106656)
        whenever(storage.getBlockByHeightStalePrioritized(3104639)).thenReturn(null)

        val exception = assertThrows(BlockValidatorException.NoCheckpointBlock::class.java) {
            validator.validate(block, block(height = 3106655))
        }

        assertEquals(3104639, exception.height)
    }

    private fun block(height: Int) = Block().apply { this.height = height }

    private companion object {
        const val HEIGHT_INTERVAL = 2016L
        const val TARGET_TIMESPAN = 302400L
        const val MAX_TARGET_BITS = 0x1e0fffffL
    }
}

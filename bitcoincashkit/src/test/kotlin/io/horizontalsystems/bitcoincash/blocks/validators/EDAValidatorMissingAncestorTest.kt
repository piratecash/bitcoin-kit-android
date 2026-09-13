package io.horizontalsystems.bitcoincash.blocks.validators

import io.horizontalsystems.bitcoincash.blocks.BitcoinCashBlockValidatorHelper
import io.horizontalsystems.bitcoincore.blocks.BlockMedianTimeHelper
import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.models.Block
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * EDA deliberately stays out of the missing-ancestor recovery: it needs a complete median-time
 * window, which a backfilled single block cannot provide.
 */
class EDAValidatorMissingAncestorTest {

    private val storage = mock<IStorage>()
    private val medianTimeHelper = mock<BlockMedianTimeHelper>()
    private val validator = EDAValidator(
        maxTargetBits = MAX_TARGET_BITS,
        blockValidatorHelper = BitcoinCashBlockValidatorHelper(storage),
        blockMedianTimeHelper = medianTimeHelper
    )

    @Test
    fun `validate - cursor block missing - throws NoPreviousBlock and not NoCheckpointBlock`() {
        val block = block(height = 600000)
        val previousBlock = block(height = block.height - 1)
        whenever(storage.getBlockByHeightStalePrioritized(block.height - 7)).thenReturn(null)

        assertThrows(BlockValidatorException.NoPreviousBlock::class.java) {
            validator.validate(block, previousBlock)
        }
    }

    @Test
    fun `validate - lookup anchors on previousBlock with step 6`() {
        val block = block(height = 600000)
        val previousBlock = block(height = block.height - 1)
        whenever(storage.getBlockByHeightStalePrioritized(block.height - 7)).thenReturn(null)

        assertThrows(BlockValidatorException.NoPreviousBlock::class.java) {
            validator.validate(block, previousBlock)
        }

        verify(storage).getBlockByHeightStalePrioritized(block.height - 7)
    }

    private fun block(height: Int) = Block().apply {
        this.height = height
        bits = BLOCK_BITS
    }

    private companion object {
        const val MAX_TARGET_BITS = 0x1d00ffffL
        const val BLOCK_BITS = 0x1a0fffffL
    }
}

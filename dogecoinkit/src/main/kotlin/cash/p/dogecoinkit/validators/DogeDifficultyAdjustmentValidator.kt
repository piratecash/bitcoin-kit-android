package cash.p.dogecoinkit.validators

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.blocks.validators.IBlockChainedValidator
import io.horizontalsystems.bitcoincore.crypto.CompactBits
import io.horizontalsystems.bitcoincore.managers.BlockValidatorHelper
import io.horizontalsystems.bitcoincore.models.Block
import java.math.BigInteger

class DogeDifficultyAdjustmentValidator(
    private val validatorHelper: BlockValidatorHelper,
    private val maxTargetBits: Long
) : IBlockChainedValidator {

    private companion object {
        const val DIFF_CHANGE_TARGET = 145000    // Block height at which Digishield activates
        const val MIN_DIFF_BLOCK_HEIGHT = 157500 // Height at which min difficulty blocks are allowed
        const val TARGET_TIMESPAN = 4 * 60 * 60  // 4 hours (pre-Digishield)
        const val TARGET_TIMESPAN_NEW = 60       // 60 seconds (Digishield)
        const val TARGET_SPACING = 60            // 1 minute target block time
    }

    override fun isBlockValidatable(block: Block, previousBlock: Block): Boolean {
        return true
    }

    override fun validate(block: Block, previousBlock: Block) {
        // Check if this is a minimum difficulty block
        if (isMinDifficultyAllowed(previousBlock, block)) {
            if (block.bits != maxTargetBits) {
                throw BlockValidatorException.NotEqualBits(
                    "1: ${block.bits} != $maxTargetBits"
                )
            }
            return
        }

        // Check if we're using the new difficulty protocol (Digishield)
        val newDifficultyProtocol = previousBlock.height + 1 >= DIFF_CHANGE_TARGET

        // Calculate the difficulty adjustment interval
        val difficultyAdjustmentInterval = if (newDifficultyProtocol) 1 else TARGET_TIMESPAN / TARGET_SPACING

        // If we're not at a difficulty adjustment interval, bits should match the previous block
        if ((previousBlock.height + 1) % difficultyAdjustmentInterval != 0) {
            if (block.bits != previousBlock.bits) {
                throw BlockValidatorException.NotEqualBits(
                    "2: ${block.bits} != ${previousBlock.bits}"
                )
            }
            return
        }

        // We're at a difficulty adjustment interval - calculate new difficulty

        // Determine how many blocks to go back
        val blocksToGoBack = if (difficultyAdjustmentInterval > 1 &&
            previousBlock.height + 1 != difficultyAdjustmentInterval) {
            difficultyAdjustmentInterval
        } else {
            difficultyAdjustmentInterval - 1
        }

        // Find the block at the beginning of the adjustment period
        val firstBlock = getAdjustmentBlock(previousBlock, blocksToGoBack)
            ?: return // Not enough blocks, skip validation

        // Calculate new target based on time difference
        val newBits = calculateNextWorkRequired(previousBlock, firstBlock, newDifficultyProtocol)

        // Check if the block's bits match our calculation
        if (newBits != block.bits) {
            throw BlockValidatorException.NotEqualBits(
                "3: ${block.bits} != $newBits"
            )
        }
    }

    private fun getAdjustmentBlock(previousBlock: Block, blocksToGoBack: Int): Block? {
        var cursor = validatorHelper.getPrevious(previousBlock, 1) ?: return null

        if (blocksToGoBack > 1) {
            cursor = validatorHelper.getPrevious(cursor, blocksToGoBack - 1) ?: return null
        }

        return cursor
    }

    private fun isMinDifficultyAllowed(previousBlock: Block, block: Block): Boolean {
        // Only allowed after a certain height
        if (previousBlock.height < MIN_DIFF_BLOCK_HEIGHT) {
            return false
        }

        // Allow minimum difficulty if block time is more than 2*TARGET_SPACING
        return block.timestamp > previousBlock.timestamp + TARGET_SPACING * 2
    }

    private fun calculateNextWorkRequired(
        previousBlock: Block,
        firstBlock: Block,
        isDigishield: Boolean
    ): Long {
        // Get the actual time span between blocks
        var actualTimespan = (previousBlock.timestamp - firstBlock.timestamp).toInt()
        val targetTimespan = if (isDigishield) TARGET_TIMESPAN_NEW else TARGET_TIMESPAN

        // Apply different timespan adjustments based on algorithm
        if (isDigishield) {
            // DigiShield: amplitude filter then clamp
            actualTimespan = targetTimespan + (actualTimespan - targetTimespan) / 8
            actualTimespan = actualTimespan.coerceIn(
                targetTimespan - targetTimespan / 4,
                targetTimespan + targetTimespan / 2
            )
        } else {
            // Original algorithm: height-dependent clamping
            actualTimespan = when {
                previousBlock.height + 1 > 10000 -> actualTimespan.coerceIn(
                    targetTimespan / 4,
                    targetTimespan * 4
                )
                previousBlock.height + 1 > 5000 -> actualTimespan.coerceIn(
                    targetTimespan / 8,
                    targetTimespan * 4
                )
                else -> actualTimespan.coerceIn(
                    targetTimespan / 16,
                    targetTimespan * 4
                )
            }
        }

        // Calculate new target
        var newTarget = CompactBits.decode(previousBlock.bits)
        newTarget = newTarget.multiply(BigInteger.valueOf(actualTimespan.toLong()))
            .divide(BigInteger.valueOf(targetTimespan.toLong()))

        // Make sure new target doesn't exceed maximum
        val maxTarget = CompactBits.decode(maxTargetBits)
        if (newTarget > maxTarget) {
            newTarget = maxTarget
        }

        return CompactBits.encode(newTarget)
    }
}
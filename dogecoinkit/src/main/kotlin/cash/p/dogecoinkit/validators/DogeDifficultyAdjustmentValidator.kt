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
        const val DIFF_CHANGE_TARGET = 145000
        const val MIN_DIFF_BLOCK_HEIGHT = 157500  // Minimum difficulty blocks only allowed after this height
        const val TARGET_TIMESPAN = (4 * 60 * 60)  // 4h per difficulty cycle, on average.
        const val TARGET_TIMESPAN_NEW = 60  // 60s per difficulty cycle, on average. Kicks in after block 145k.
        const val TARGET_SPACING = 60  // 1 minute per block.
        const val INTERVAL = TARGET_TIMESPAN / TARGET_SPACING;
        const val INTERVAL_NEW = TARGET_TIMESPAN_NEW / TARGET_SPACING;
    }

    override fun isBlockValidatable(block: Block, previousBlock: Block): Boolean {
        return true
    }

    /**
     * Check if minimum difficulty is allowed for a block
     * This is the general minimum difficulty check that applies regardless of algorithm
     */
    private fun allowMinDifficultyForBlock(previousBlock: Block, block: Block): Boolean {
        // Minimum difficulty blocks are only allowed after height 157500
        if (previousBlock.height < MIN_DIFF_BLOCK_HEIGHT) {
            return false
        }

        // Allow minimum difficulty if block time is more than 2*TARGET_SPACING
        return block.timestamp > previousBlock.timestamp + TARGET_SPACING * 2
    }

    /**
     * Check if minimum difficulty is allowed for DigiShield blocks
     * This specifically checks for DigiShield-specific conditions
     */
    private fun allowDigishieldMinDifficultyForBlock(previousBlock: Block, block: Block): Boolean {
        // First, check general minimum difficulty conditions
        if (!allowMinDifficultyForBlock(previousBlock, block)) {
            return false
        }

        // Additionally check if we're using DigiShield algorithm (after block 145000)
        // This should almost always be true for modern Dogecoin blocks
        return previousBlock.height >= DIFF_CHANGE_TARGET
    }

    override fun validate(block: Block, previousBlock: Block) {
        // Check for min difficulty blocks first
        if (allowDigishieldMinDifficultyForBlock(previousBlock, block)) {
            // For min difficulty blocks, verify bits equals maxTargetBits
            if (block.bits != maxTargetBits) {
                throw BlockValidatorException.NotEqualBits(
                    "1: ${block.bits} != $maxTargetBits"
                )
            }
            return
        }

        val storedPrev = checkNotNull(validatorHelper.getPrevious(block, 1)) {
            BlockValidatorException.NoCheckpointBlock()
        }

        val newDiffAlgo = storedPrev.height + 1 >= DIFF_CHANGE_TARGET
        var retargetInterval = INTERVAL
        var retargetTimespan = TARGET_TIMESPAN

        if (newDiffAlgo) {
            retargetInterval = INTERVAL_NEW
            retargetTimespan = TARGET_TIMESPAN_NEW
        }

        // If we're not at a difficulty adjustment interval,
        // just check that difficulty remains the same
        if ((storedPrev.height + 1) % retargetInterval != 0) {
            // For non-retargeting blocks, we still need to check if minimum difficulty is allowed
            // This is relevant for testnet, even with DigiShield algorithm
            if (allowMinDifficultyForBlock(previousBlock, block)) {
                if (block.bits != maxTargetBits) {
                    throw BlockValidatorException.NotEqualBits(
                        "2: ${block.bits} != $maxTargetBits"
                    )
                }
                return
            }

            // Otherwise, check that bits remain the same
            if (block.bits != previousBlock.bits) {
                throw BlockValidatorException.NotDifficultyTransitionEqualBits(
                    "${block.bits} != ${previousBlock.bits}"
                )
            }
            return
        }

        // At a difficulty adjustment interval - calculate the new difficulty

        // We need to find a block far back in the chain
        val now = System.currentTimeMillis()

        var cursor: Block? = validatorHelper.getPrevious(previousBlock, 1)
        var goBack = retargetInterval - 1
        if (cursor != null && cursor.height + 1 != retargetInterval) {
            goBack = retargetInterval
        }

        if (cursor == null) {
            return // looks like we don't have enough saved blocks
        }
        cursor = validatorHelper.getPrevious(cursor, goBack - 1)

        // We used checkpoints...
        if (cursor == null) {
            return
        }

        val blockIntervalAgo = cursor
        var timespan = (previousBlock.timestamp - blockIntervalAgo.timestamp).toInt()
        val targetTimespan = retargetTimespan

        // Apply appropriate timespan limits based on algorithm
        if (newDiffAlgo) {
            // DigiShield implementation - amplitude filter
            timespan = retargetTimespan + (timespan - retargetTimespan) / 8

            // DigiShield specific limits
            timespan = timespan.coerceIn(
                retargetTimespan - retargetTimespan / 4,
                retargetTimespan + retargetTimespan / 2
            )
        } else {
            // Limit the adjustment step for original algorithm based on height
            timespan = when {
                storedPrev.height + 1 > 10000 -> timespan.coerceIn(
                    targetTimespan / 4,
                    targetTimespan * 4
                )
                storedPrev.height + 1 > 5000 -> timespan.coerceIn(
                    targetTimespan / 8,
                    targetTimespan * 4
                )
                else -> timespan.coerceIn(targetTimespan / 16, targetTimespan * 4)
            }
        }

        // Calculate new target based on timespan
        var newDifficulty = CompactBits.decode(previousBlock.bits)
        newDifficulty = newDifficulty.multiply(BigInteger.valueOf(timespan.toLong()))
            .divide(BigInteger.valueOf(targetTimespan.toLong()))

        // Cap at proof of work limit
        val maxTarget = CompactBits.decode(maxTargetBits)
        if (newDifficulty > maxTarget) {
            newDifficulty = maxTarget
        }

        // Encode the calculated difficulty to bits format
        val newTargetBits = CompactBits.encode(newDifficulty)

        // Compare the calculated bits with the received bits
        if (newTargetBits != block.bits) {
            throw BlockValidatorException.NotDifficultyTransitionEqualBits(
                "3: $newTargetBits != Actual: ${block.bits}"
            )
        }
    }
}
package cash.p.dogecoinkit.validators

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.blocks.validators.IBlockChainedValidator
import io.horizontalsystems.bitcoincore.crypto.CompactBits
import io.horizontalsystems.bitcoincore.managers.BlockValidatorHelper
import io.horizontalsystems.bitcoincore.models.Block
import java.math.BigInteger
import java.util.logging.Logger

class DogeDifficultyAdjustmentValidator(
    private val validatorHelper: BlockValidatorHelper,
    private val maxTargetBits: Long
) : IBlockChainedValidator {

    private val logger = Logger.getLogger("DogeDifficultyAdjustmentValidator")

    private companion object {
        const val DIFF_CHANGE_TARGET = 145000
        const val TARGET_TIMESPAN = (4 * 60 * 60)  // 4h per difficulty cycle, on average.
        const val TARGET_TIMESPAN_NEW =
            60  // 60s per difficulty cycle, on average. Kicks in after block 145k.
        const val TARGET_SPACING = 60  // 1 minutes per block.
        const val INTERVAL = TARGET_TIMESPAN / TARGET_SPACING;
        const val INTERVAL_NEW = TARGET_TIMESPAN_NEW / TARGET_SPACING;
    }

    override fun isBlockValidatable(block: Block, previousBlock: Block): Boolean {
        return true
    }

    override fun validate(block: Block, previousBlock: Block) {
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

        if ((storedPrev.height + 1) % retargetInterval != 0) {
            if (block.bits != previousBlock.bits) {
                throw BlockValidatorException.NotDifficultyTransitionEqualBits()
            }
            return
        }

        // We need to find a block far back in the chain. It's OK that this is expensive because it only occurs every
        // two weeks after the initial block chain download.
        val now = System.currentTimeMillis()

        var cursor: Block? = validatorHelper.getPrevious(previousBlock, 1)
        var goBack = retargetInterval - 1
        if (cursor != null && cursor.height + 1 != retargetInterval) {
            goBack = retargetInterval
        }

        if (cursor == null) {
            // This should never happen. If it does, it means we are following an incorrect or busted chain.
            throw BlockValidatorException.NotDifficultyTransitionEqualBits()
        }
        cursor = validatorHelper.getPrevious(cursor, goBack - 1)

        //We used checkpoints...
        if (cursor == null) {
            logger.info("Difficulty transition: Hit checkpoint!")
            return
        }

        val elapsed = System.currentTimeMillis() - now
        if (elapsed > 50) {
            logger.info("Difficulty transition traversal took ${elapsed}ms")
        }

        val blockIntervalAgo = cursor
        var timespan = (previousBlock.timestamp - blockIntervalAgo.timestamp).toInt()
        val targetTimespan = retargetTimespan

        if (newDiffAlgo) {
            timespan = retargetTimespan + (timespan - retargetTimespan) / 8
            timespan = timespan.coerceIn(
                retargetTimespan - retargetTimespan / 4,
                retargetTimespan + retargetTimespan / 2
            )
        } else {
            // Limit the adjustment step.
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

        var newDifficulty = CompactBits.decode(previousBlock.bits)
        newDifficulty = newDifficulty.multiply(BigInteger.valueOf(timespan.toLong()))
            .divide(BigInteger.valueOf(targetTimespan.toLong()))

        if (newDifficulty > CompactBits.decode(maxTargetBits)) {
            logger.info("Difficulty hit proof of work limit: ${newDifficulty.toString(16)}")
            newDifficulty = CompactBits.decode(maxTargetBits)
        }

        val accuracyBytes = (block.bits ushr 24) - 3
        val receivedDifficulty = CompactBits.decode(block.bits)

        // The calculated difficulty is to a higher precision than received, so reduce here.
        val mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes.toInt() * 8)
        newDifficulty = newDifficulty.and(mask)

        if (newDifficulty != receivedDifficulty) {
            throw BlockValidatorException.NotDifficultyTransitionEqualBits()
        }
    }
}

package cash.p.dogecoinkit.validators

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.blocks.validators.IBlockChainedValidator
import io.horizontalsystems.bitcoincore.crypto.CompactBits
import io.horizontalsystems.bitcoincore.managers.BlockValidatorHelper
import io.horizontalsystems.bitcoincore.models.Block
import java.math.BigInteger
import kotlin.math.max
import kotlin.math.min

class DogeDifficultyAdjustmentValidator(
    private val validatorHelper: BlockValidatorHelper,
    private val maxTargetBits: Long
) : IBlockChainedValidator {

    override fun isBlockValidatable(block: Block, previousBlock: Block): Boolean {
        return true
    }

    override fun validate(block: Block, previousBlock: Block) {
        val lastBlock = checkNotNull(validatorHelper.getPrevious(block, 1)) {
            BlockValidatorException.NoCheckpointBlock()
        }

        var actualTimespan = block.timestamp - lastBlock.timestamp
        actualTimespan = max(60, min(600, actualTimespan))

        var newTarget = CompactBits.decode(previousBlock.bits)
        newTarget = newTarget.multiply(BigInteger.valueOf(actualTimespan))
        newTarget = newTarget.divide(BigInteger.valueOf(150))

        val newDifficulty = min(CompactBits.encode(newTarget), maxTargetBits)

        if (newDifficulty != block.bits) {
            throw BlockValidatorException.NotDifficultyTransitionEqualBits()
        }
    }
}

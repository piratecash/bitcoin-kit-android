package io.horizontalsystems.cosantakit.validators

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.blocks.validators.IBlockChainedValidator
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.storage.BlockHeader

internal class CosantaProofOfStakeValidator :
    IBlockChainedValidator {
    override fun isBlockValidatable(block: Block, previousBlock: Block): Boolean {
        return block.isProofOfStake()
    }

    override fun validate(block: Block, previousBlock: Block) {
        val header: BlockHeader = block.merkleBlock?.header
            ?: throw BlockValidatorException("Missing Merkle block header for proof-of-stake block")

        if (header.posBlockSig.isNullOrEmpty()) {
            throw BlockValidatorException("Missing proof-of-stake block signature")
        }

        if (!block.previousBlockHash.contentEquals(previousBlock.headerHash)) {
            throw BlockValidatorException("Previous block hash mismatch")
        }
    }
}

private fun ByteArray?.isNullOrEmpty(): Boolean = this == null || this.isEmpty()


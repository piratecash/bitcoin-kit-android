package io.horizontalsystems.piratecashkit.validators

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.blocks.validators.IBlockChainedValidator
import io.horizontalsystems.bitcoincore.core.DoubleSha256Hasher
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.models.Block

class PirateCashProofOfStakeValidator : IBlockChainedValidator {

    companion object {
        const val FIRST_POS_V2BLOCK = 78000
    }

    override fun isBlockValidatable(block: Block, previousBlock: Block): Boolean {
        // 1. Check if the block is PoS
        if (!block.isProofOfStakeV2()) {
            return false // This block is not PoS, not suitable for PoS validator
        }

        // 2. Check that we are in the PoS period or that this PoS is allowed
        val blockHeight = previousBlock.height + 1
        val isPoSActive = previousBlock.isProofOfStakeV2()
        val isPoSV2Enforced = isPoSV2EnforcedHeight(blockHeight, FIRST_POS_V2BLOCK)

        if (!isPoSActive && !isPoSV2Enforced) {
            return false // PoS is not active yet
        }

        // 3. For PoSv2, check the corresponding version bit
        if (block.isProofOfStakeV2()) {
            val isPoSv2Active = previousBlock.isProofOfStakeV2()

            if (!isPoSv2Active && !isPoSV2Enforced) {
                return false // PoSv2 is not active yet
            }
        }

        return true
    }

    override fun validate(block: Block, previousBlock: Block) {
        if(!isBlockValidatable(block, previousBlock)) return

        // 1. Check if header exists
        if (block.headerHash.isEmpty()) {
            throw BlockValidatorException.NoHeader()
        }

        // 2. Check if previous block exists
        if (previousBlock.headerHash.isEmpty()) {
            throw BlockValidatorException.NoPreviousBlock()
        }

        // 3. Check if previous block hash matches
        if (!block.previousBlockHash.contentEquals(previousBlock.headerHash)) {
            throw BlockValidatorException.WrongPreviousHeader()
        }

        // 5. Check block hash
        val calculatedHash = calculateBlockHash(block)
        if (!calculatedHash.contentEquals(block.headerHash)) {
            throw BlockValidatorException.WrongBlockHash(
                expected = block.headerHash,
                actual = calculatedHash
            )
        }
    }

    // Calculate block hash
    private fun calculateBlockHash(block: Block): ByteArray {
        // Get block header bytes
        val headerBytes = getBlockHeaderBytes(block)

        // For PoS blocks, hashing may differ
        // PirateCash uses SHA-256 or Scrypt depending on version
        return DoubleSha256Hasher().hash(headerBytes)
    }

    // Get block header bytes for hashing
    private fun getBlockHeaderBytes(block: Block): ByteArray {
        return BitcoinOutput()
            .writeInt(block.version)
            .write(block.previousBlockHash)
            .write(block.merkleRoot)
            .writeUnsignedInt(block.timestamp)
            .writeUnsignedInt(block.bits)
            .writeUnsignedInt(block.nonce)
            .write(block.merkleBlock?.header?.posStakeHash!!)
            .writeInt(block.merkleBlock?.header?.posStakeN!!)
            .toByteArray()
    }
}

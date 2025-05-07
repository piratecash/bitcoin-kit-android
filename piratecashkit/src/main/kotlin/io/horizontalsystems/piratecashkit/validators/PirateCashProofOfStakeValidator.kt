package io.horizontalsystems.piratecashkit.validators

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.blocks.validators.IBlockChainedValidator
import io.horizontalsystems.bitcoincore.core.DoubleSha256Hasher
import io.horizontalsystems.bitcoincore.models.Block
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

        // 4. Check bits (difficulty)
        // For PoS we verify that bits match expected values
        if (block.bits != calculateExpectedBits(previousBlock)) {
            throw BlockValidatorException.NotEqualBits(
                "Expected: ${
                    calculateExpectedBits(
                        previousBlock
                    )
                }, Actual: ${block.bits}"
            )
        }

        // 5. Check block hash
        val calculatedHash = calculateBlockHash(block)
        if (!calculatedHash.contentEquals(block.headerHash)) {
            throw BlockValidatorException.WrongBlockHash(
                expected = block.headerHash,
                actual = calculatedHash
            )
        }

        // 6. PoS specific checks
        validatePoSBlock(block, previousBlock)
    }

    // Validate PoS block
    private fun validatePoSBlock(block: Block, previousBlock: Block) {
        val blockHeader = block.merkleBlock?.header ?: return

        // Check if block signature exists
        if (blockHeader.posBlockSig == null || blockHeader.posBlockSig!!.isEmpty()) {
            throw BlockValidatorException("Missing PoS signature")
        }

        // Check if stake information exists
        if (blockHeader.posStakeHash == null || blockHeader.posStakeHash!!.isEmpty()) {
            throw BlockValidatorException("Missing stake information")
        }

        // Check block timestamp
        if (block.timestamp <= previousBlock.timestamp) {
            throw BlockValidatorException("Block timestamp violation")
        }

        // Client wallets typically don't verify stake kernel hash or stake modifier
        // as it requires access to the full chain and is computationally expensive
        // Such checks are performed by network nodes, and the wallet usually trusts this validation
    }

    // Calculate expected difficulty for current block
    private fun calculateExpectedBits(previousBlock: Block): Long {
        // For PoS systems, difficulty may change with each block
        // This is a simplified implementation - in a real system the algorithm would be more complex

        // PirateCash uses DarkGravityWave or similar algorithm
        // For mobile wallets, typically just accepting difficulty from block header

        return previousBlock.bits // Simplified: return previous difficulty
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
        // Serialize block header
        val buffer = ByteBuffer.allocate(80)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(block.version)
        buffer.put(block.previousBlockHash)
        buffer.put(block.merkleRoot)
        buffer.putInt(block.timestamp.toInt())
        buffer.putInt(block.bits.toInt())
        buffer.putInt(block.nonce.toInt())

        return buffer.array()
    }
}

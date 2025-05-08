package io.horizontalsystems.piratecashkit.validators

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.blocks.validators.IBlockChainedValidator
import io.horizontalsystems.bitcoincore.crypto.CompactBits
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.piratecashkit.ScryptHasher
import java.math.BigInteger

internal class PirateCashProofOfWorkValidator(private val scryptHasher: ScryptHasher) : IBlockChainedValidator {

    override fun isBlockValidatable(block: Block, previousBlock: Block)
            = block.isProofOfWork() && !previousBlock.isProofOfStakeV2()

    override fun validate(block: Block, previousBlock: Block) {
        if(!isBlockValidatable(block, previousBlock)) return

        val blockHeaderData = getSerializedBlockHeader(block)

        val powHash = scryptHasher.hash(blockHeaderData).toHexString()

        check(block.nonce == 0L || BigInteger(powHash, 16) < CompactBits.decode(block.bits)) {
            throw BlockValidatorException.InvalidProofOfWork()
        }
    }

    private fun getSerializedBlockHeader(block: Block): ByteArray {
        return BitcoinOutput()
            .writeInt(block.version)
            .write(block.previousBlockHash)
            .write(block.merkleRoot)
            .writeUnsignedInt(block.timestamp)
            .writeUnsignedInt(block.bits)
            .writeUnsignedInt(block.nonce)
            .toByteArray()
    }
}

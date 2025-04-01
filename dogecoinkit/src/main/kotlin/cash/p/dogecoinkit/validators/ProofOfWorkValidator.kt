package cash.p.dogecoinkit.validators

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.blocks.validators.IBlockChainedValidator
import io.horizontalsystems.bitcoincore.crypto.CompactBits
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.models.Block
import cash.p.dogecoinkit.ScryptHasher
import cash.p.dogecoinkit.serializers.AuxHeader
import java.math.BigInteger

class ProofOfWorkValidator(private val scryptHasher: ScryptHasher) : IBlockChainedValidator {

    override fun validate(block: Block, previousBlock: Block) {
        val blockHeaderData = getSerializedBlockHeader(block)

        val powHash = scryptHasher.hash(blockHeaderData).toHexString()

        if(block.merkleBlock?.extraData as? AuxHeader != null) {
            val auxHeader = block.merkleBlock?.extraData as AuxHeader
            validateAuxPow(auxHeader, block.bits)
        } else {
            check(BigInteger(powHash, 16) < CompactBits.decode(block.bits)) {
                throw BlockValidatorException.InvalidProofOfWork()
            }
        }
    }

    private fun validateAuxPow(auxHeader: AuxHeader, bits: Long) {
        val powHash = scryptHasher.hash(auxHeader.constructParentHeader()).toHexString()
        check(BigInteger(powHash, 16) < CompactBits.decode(bits)) {
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

    override fun isBlockValidatable(block: Block, previousBlock: Block): Boolean {
        return true
    }

}

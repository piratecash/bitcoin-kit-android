package io.horizontalsystems.cosantakit.messages

import io.horizontalsystems.bitcoincore.core.DoubleSha256Hasher
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.horizontalsystems.cosantakit.X11HasherExt
import io.horizontalsystems.cosantakit.validators.isProofOfStake

internal class CosantaBlockHeaderParser {
    private val hasherX11Ext = X11HasherExt()
    private val hasherDoubleSha256 = DoubleSha256Hasher()

    fun parse(input: BitcoinInputMarkable): BlockHeader {
        input.mark()
        val payloadPos = input.readBytes(80 + 32 + 4)
        input.reset()

        input.mark()
        val payloadNoPos = input.readBytes(80)
        input.reset()

        val version = input.readInt()
        val previousBlockHeaderHash = input.readBytes(32)
        val merkleRoot = input.readBytes(32)
        val timestamp = input.readUnsignedInt()
        val bits = input.readUnsignedInt()
        val nonce = input.readUnsignedInt()

        val proofOfStake = isProofOfStake(version)
        var posBlockSig: ByteArray? = null
        var posStakeHash: ByteArray? = null
        var posStakeN: Int? = null
        if (proofOfStake) {
            posStakeHash = input.readBytes(32)
            posStakeN = input.readInt()
            val posBlockSigSize = input.readVarInt()
            posBlockSig = input.readBytes(posBlockSigSize.toInt())
        }

        return BlockHeader(
            version = version,
            previousBlockHeaderHash = previousBlockHeaderHash,
            merkleRoot = merkleRoot,
            timestamp = timestamp,
            bits = bits,
            nonce = nonce,
            hash = if(proofOfStake) {
                hasherDoubleSha256.hash(payloadPos)
            } else{
                hasherX11Ext.hash(payloadNoPos)
            },
            posBlockSig = posBlockSig,
            posStakeHash = posStakeHash,
            posStakeN = posStakeN
        )
    }
}

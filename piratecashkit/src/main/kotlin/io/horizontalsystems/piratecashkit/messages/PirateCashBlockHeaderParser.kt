package io.horizontalsystems.piratecashkit.messages

import io.horizontalsystems.bitcoincore.core.DoubleSha256Hasher
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.horizontalsystems.piratecashkit.ScryptHasher
import io.horizontalsystems.piratecashkit.validators.isProofOfStakeV2

internal class PirateCashBlockHeaderParser {
    private val scryptHasher = ScryptHasher()
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

        val proofOfStake = isProofOfStakeV2(version)
        var posBlockSig: ByteArray? = null
        var posStakeHash: ByteArray? = null
        var posStakeN: Int? = null
        if (proofOfStake) {
            posStakeHash = input.readBytes(32)
            posStakeN = input.readInt()
            val posBlockSigSize = input.readVarInt()
            posBlockSig = input.readBytes(posBlockSigSize.toInt())
        }

        val nFlags = input.readUnsignedInt().toInt()

        val hash = if (version < 4) {
            scryptHasher.hash(payloadNoPos)
        } else {
            if(proofOfStake) {
                hasherDoubleSha256.hash(payloadPos)
            } else {
                hasherDoubleSha256.hash(payloadNoPos)
            }
        }

        return BlockHeader(
            version = version,
            previousBlockHeaderHash = previousBlockHeaderHash,
            merkleRoot = merkleRoot,
            timestamp = timestamp,
            bits = bits,
            nonce = nonce,
            hash = hash,
            posBlockSig = posBlockSig,
            posStakeHash = posStakeHash,
            posStakeN = posStakeN
        )
    }
}

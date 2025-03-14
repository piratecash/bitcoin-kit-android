package cash.p.dogecoinkit.serializers

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.io.UnsafeByteArrayOutputStream
import io.horizontalsystems.bitcoincore.utils.Utils
import java.io.ByteArrayOutputStream


object AuxPowSerializer {
    fun deserialize(input: BitcoinInputMarkable): AuxHeader {
        val header = AuxHeader()
        header.parentCoinbaseVerion = input.readUnsignedInt()
        header.parentCoinbaseTxInCount = input.readVarInt()
        header.parentCointbasePrevOut = input.readBytes(36) // Always the same on coinbase
        header.parentCoinbaseInScriptLength = input.readVarInt()
        header.parentCoinbaseInScript =
            input.readBytes(header.parentCoinbaseInScriptLength.toInt()) // Script length is limited so this cast should be fine.
        header.parentCoinBaseSequenceNumber = input.readUnsignedInt()
        header.parentCoinbaseTxOutCount = input.readVarInt()

        val outs = ArrayList<AuxCoinbaseOut>()
        for (i in 0..<header.parentCoinbaseTxOutCount) {
            val out = AuxCoinbaseOut()
            out.amount = input.readLong()
            out.scriptLength = input.readVarInt()
            out.script =
                input.readBytes(out.scriptLength.toInt()) // Script length is limited so this cast should be fine.
            outs.add(out)
        }
        header.parentCoinbaseOuts = outs

        header.parentCoinbaseLockTime = input.readUnsignedInt()

        header.parentBlockHeaderHash = input.readBytes(32)
        header.numOfCoinbaseLinks = input.readVarInt()
        val coinbaseLinks = ArrayList<ByteArray>()
        for (i in 0..<header.numOfCoinbaseLinks) {
            coinbaseLinks.add(input.readBytes(32))
        }
        header.coinbaseLinks = coinbaseLinks
        header.coinbaseBranchBitmask = input.readUnsignedInt()

        header.numOfAuxChainLinks = input.readVarInt()
        val auxChainLinks = ArrayList<ByteArray>()
        for (i in 0..<header.numOfAuxChainLinks) {
            auxChainLinks.add(input.readBytes(32))
        }
        header.auxChainLinks = auxChainLinks
        header.auxChainBranchBitmask = input.readUnsignedInt()

        header.parentBlockVersion = input.readUnsignedInt()
        header.parentBlockPrev = input.readBytes(32)
        header.parentBlockMerkleRoot = input.readBytes(32)
        header.parentBlockTime = input.readUnsignedInt()
        header.parentBlockBits = input.readUnsignedInt()
        header.parentBlockNonce = input.readUnsignedInt()
        return header
    }
}


class AuxHeader {
    // Parent coinbase
    var parentCoinbaseVerion: Long = 0
    var parentCoinbaseTxInCount: Long = 0
    var parentCointbasePrevOut: ByteArray = byteArrayOf()
    var parentCoinbaseInScriptLength: Long = 0
    var parentCoinbaseInScript: ByteArray = byteArrayOf()
    var parentCoinBaseSequenceNumber: Long = 0
    var parentCoinbaseTxOutCount: Long = 0
    var parentCoinbaseOuts: ArrayList<AuxCoinbaseOut>? = null
    var parentCoinbaseLockTime: Long = 0

    // Coinbase link
    var parentBlockHeaderHash: ByteArray? = null
    var numOfCoinbaseLinks: Long = 0
    var coinbaseLinks: ArrayList<ByteArray>? = null
    var coinbaseBranchBitmask: Long = 0

    // Aux chanin link
    var numOfAuxChainLinks: Long = 0
    var auxChainLinks: ArrayList<ByteArray>? = null
    var auxChainBranchBitmask: Long = 0

    // Parent block header
    var parentBlockVersion: Long = 0
    var parentBlockPrev: ByteArray? = null
    var parentBlockMerkleRoot: ByteArray? = null
    var parentBlockTime: Long = 0
    var parentBlockBits: Long = 0
    var parentBlockNonce: Long = 0


    fun constructParentHeader(): ByteArray {
        val stream: ByteArrayOutputStream = UnsafeByteArrayOutputStream(80)
        Utils.uint32ToByteStreamLE(parentBlockVersion, stream)
        stream.write(Utils.reverseBytes(parentBlockPrev))
        stream.write(Utils.reverseBytes(parentBlockMerkleRoot))
        Utils.uint32ToByteStreamLE(parentBlockTime, stream)
        Utils.uint32ToByteStreamLE(parentBlockBits, stream)
        Utils.uint32ToByteStreamLE(parentBlockNonce, stream)
        return stream.toByteArray()
    }
}

class AuxCoinbaseOut {
    var amount: Long = 0
    var scriptLength: Long = 0
    var script: ByteArray = byteArrayOf()
}
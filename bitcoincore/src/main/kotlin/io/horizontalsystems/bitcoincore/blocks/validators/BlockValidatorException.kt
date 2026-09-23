package io.horizontalsystems.bitcoincore.blocks.validators

import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.extensions.toReversedHex

open class BlockValidatorException(msg: String) : RuntimeException(msg) {
    class NoHeader : BlockValidatorException("No Header")
    class NoCheckpointBlock(val height: Int? = null) :
        BlockValidatorException("No Checkpoint Block" + (height?.let { " at height $it" } ?: ""))

    class AncestorDownloadQueued(val height: Int) :
        BlockValidatorException("Missing ancestor at height $height queued for download")

    class OrphanBlock(block: ByteArray? = null) :
        BlockValidatorException("Orphan Block: ${block?.toHexString()}")
    class NoPreviousBlock(block: ByteArray? = null) :
        BlockValidatorException("No PreviousBlock: ${block?.toHexString()}")

    class WrongPreviousHeader : BlockValidatorException("Wrong Previous Header Hash")
    class NotEqualBits(msg: String? = null) : BlockValidatorException("Not Equal Bits: $msg")
    class NotDifficultyTransitionEqualBits(msg: String? = null) :
        BlockValidatorException("Not Difficulty Transition Equal Bits: $msg")

    class InvalidProofOfWork : BlockValidatorException("Invalid Prove of Work")
    class WrongBlockHash(expected: ByteArray, actual: ByteArray) :
        BlockValidatorException("Wrong Block Hash ${actual.toReversedHex()} vs expected ${expected.toReversedHex()}")
}

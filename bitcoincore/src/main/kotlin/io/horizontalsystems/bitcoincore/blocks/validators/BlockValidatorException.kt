package io.horizontalsystems.bitcoincore.blocks.validators

import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.extensions.toReversedHex

open class BlockValidatorException(msg: String) : RuntimeException(msg) {
    class NoHeader : BlockValidatorException("No Header")
    class NoCheckpointBlock : BlockValidatorException("No Checkpoint Block")
    class NoPreviousBlock(block: ByteArray? = null) : BlockValidatorException("No PreviousBlock: ${block?.toHexString()}")
    class WrongPreviousHeader : BlockValidatorException("Wrong Previous Header Hash")
    class NotEqualBits : BlockValidatorException("Not Equal Bits")
    class NotDifficultyTransitionEqualBits : BlockValidatorException("Not Difficulty Transition Equal Bits")
    class InvalidProofOfWork : BlockValidatorException("Invalid Prove of Work")
    class WrongBlockHash(expected: ByteArray, actual: ByteArray) : BlockValidatorException("Wrong Block Hash ${actual.toReversedHex()} vs expected ${expected.toReversedHex()}")
}

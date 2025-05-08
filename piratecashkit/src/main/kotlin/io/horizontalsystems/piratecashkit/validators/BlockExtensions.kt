package io.horizontalsystems.piratecashkit.validators

import io.horizontalsystems.bitcoincore.models.Block

// Constants for block version flags
private const val POS_BIT: Int = 0x10000000
private const val POSV2_BITS: Int = POS_BIT or 0x08000000

/**
 * Checks if the block is PoSv2 by version bit
 */
fun Block.isProofOfStakeV2(): Boolean {
    return isProofOfStakeV2(version)
}

/**
 * Checks if the version corresponds to PoSv2
 */
fun isProofOfStakeV2(version: Int): Boolean {
    return (version and POSV2_BITS) == POSV2_BITS
}

/**
 * Checks if the block is PoW (not PoS)
 */
fun Block.isProofOfWork(): Boolean {
    return !isProofOfStakeV2()
}

/**
 * Checks if PoS is enforced at the given height
 */
fun isPoSEnforcedHeight(height: Int, firstPosBlock: Int): Boolean {
    return height >= firstPosBlock
}

/**
 * Checks if PoSv2 is enforced at the given height
 */
fun isPoSV2EnforcedHeight(height: Int, firstPosV2Block: Int): Boolean {
    return height >= firstPosV2Block
}

/**
 * Checks if we are in the PoW period
 */
fun isPowActive(height: Int, lastPowBlock: Int): Boolean {
    return height < lastPowBlock
}

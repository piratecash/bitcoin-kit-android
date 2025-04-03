package io.horizontalsystems.cosantakit.validators

import io.horizontalsystems.bitcoincore.models.Block

private const val POS_BIT: Int = 0x10000000

fun Block.isProofOfStake(): Boolean {
    return isProofOfStake(version)
}

fun isProofOfStake(version: Int): Boolean {
    return (version and POS_BIT) != 0
}
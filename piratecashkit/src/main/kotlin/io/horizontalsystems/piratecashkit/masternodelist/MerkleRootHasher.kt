package io.horizontalsystems.piratecashkit.masternodelist

import io.horizontalsystems.bitcoincore.core.IHasher
import io.horizontalsystems.bitcoincore.utils.HashUtils
import io.horizontalsystems.piratecashkit.IMerkleHasher

class MerkleRootHasher: IHasher, IMerkleHasher {

    override fun hash(data: ByteArray): ByteArray {
        return HashUtils.doubleSha256(data)
    }

    override fun hash(first: ByteArray, second: ByteArray): ByteArray {
        return HashUtils.doubleSha256(first + second)
    }
}

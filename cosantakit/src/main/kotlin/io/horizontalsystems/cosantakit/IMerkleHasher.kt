package io.horizontalsystems.cosantakit

interface IMerkleHasher {
    fun hash(first: ByteArray, second: ByteArray) : ByteArray
}
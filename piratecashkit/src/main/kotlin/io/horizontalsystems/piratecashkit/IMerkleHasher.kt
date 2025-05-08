package io.horizontalsystems.piratecashkit

interface IMerkleHasher {
    fun hash(first: ByteArray, second: ByteArray) : ByteArray
}
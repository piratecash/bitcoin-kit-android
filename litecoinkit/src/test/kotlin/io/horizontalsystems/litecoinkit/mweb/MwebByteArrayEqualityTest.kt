package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.litecoinkit.mweb.daemon.MwebCreateResult
import io.horizontalsystems.litecoinkit.mweb.storage.MwebPendingTransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MwebByteArrayEqualityTest {
    @Test
    fun equals_sameRawTransactionContent_returnsTrue() {
        assertContentEquals(
            MwebSendResult("hash", byteArrayOf(1, 2), listOf("out")),
            MwebSendResult("hash", byteArrayOf(1, 2), listOf("out")),
        )
        assertContentEquals(
            MwebPendingTransaction(byteArrayOf(1, 2), listOf("out"), "hash", 123),
            MwebPendingTransaction(byteArrayOf(1, 2), listOf("out"), "hash", 123),
        )
        assertContentEquals(
            MwebCreateResult(byteArrayOf(1, 2), listOf("out")),
            MwebCreateResult(byteArrayOf(1, 2), listOf("out")),
        )
        assertContentEquals(
            MwebPendingTransactionEntity(1, byteArrayOf(1, 2), listOf("out"), "hash", 123),
            MwebPendingTransactionEntity(1, byteArrayOf(1, 2), listOf("out"), "hash", 123),
        )
    }

    private fun assertContentEquals(first: Any, second: Any) {
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }
}

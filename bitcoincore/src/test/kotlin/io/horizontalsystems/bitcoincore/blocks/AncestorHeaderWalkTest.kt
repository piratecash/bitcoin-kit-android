package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.models.MerkleBlock
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AncestorHeaderWalkTest {

    @Test
    fun `accept - segment reaches the endpoint - resolves with every height derived from the anchor`() {
        val walk = walk()

        val result = walk.accept(chain(from = ANCHOR_HASH, count = 3))

        val resolved = assertResolved(result)
        assertEquals(listOf(TARGET_HEIGHT, TARGET_HEIGHT + 1, END_HEIGHT), resolved.headers.map { it.height })
        assertArrayEquals(hash(2), resolved.headers.first().header.hash)
    }

    @Test
    fun `accept - headers below the target - are dropped but still link the segment`() {
        val walk = walk(targetHeight = END_HEIGHT)

        val resolved = assertResolved(walk.accept(chain(from = ANCHOR_HASH, count = 3)))

        assertEquals(listOf(END_HEIGHT), resolved.headers.map { it.height })
    }

    @Test
    fun `accept - internal link is broken - aborts`() {
        val walk = walk()
        val headers = chain(from = ANCHOR_HASH, count = 3)
        headers[1] = header(hash = hash(4), previousHash = hash(99))

        assertTrue(walk.accept(headers) is AncestorHeaderWalk.Result.Abort)
    }

    @Test
    fun `accept - first header builds on no anchor - aborts`() {
        val walk = walk()

        assertTrue(walk.accept(chain(from = hash(99), count = 3)) is AncestorHeaderWalk.Result.Abort)
    }

    @Test
    fun `accept - empty batch - aborts`() {
        val walk = walk()

        assertTrue(walk.accept(emptyArray()) is AncestorHeaderWalk.Result.Abort)
    }

    @Test
    fun `accept - segment arrives in two batches - resumes from the last header and resolves`() {
        val walk = walk()
        val full = chain(from = ANCHOR_HASH, count = 3)

        assertTrue(walk.accept(arrayOf(full[0], full[1])) is AncestorHeaderWalk.Result.Continue)
        val resolved = assertResolved(walk.accept(arrayOf(full[2])))

        assertEquals(listOf(TARGET_HEIGHT, TARGET_HEIGHT + 1, END_HEIGHT), resolved.headers.map { it.height })
    }

    @Test
    fun `accept - linked chain never reaching the endpoint - aborts once it passes the endpoint height`() {
        val walk = walk()

        // The round 3 counterexample: headers that link to each other perfectly and carry any bits
        // they like. Nothing but the endpoint hash can resolve the walk.
        assertTrue(walk.accept(chain(from = ANCHOR_HASH, count = 6, endAt = null)) is AncestorHeaderWalk.Result.Abort)
    }

    @Test
    fun `accept - endpoint hash at a lower derived height - aborts`() {
        val walk = walk(endHeight = END_HEIGHT + 1)

        assertTrue(walk.accept(chain(from = ANCHOR_HASH, count = 3)) is AncestorHeaderWalk.Result.Abort)
    }

    @Test
    fun `accept - surplus headers after resolution - are ignored`() {
        val walk = walk()
        assertResolved(walk.accept(chain(from = ANCHOR_HASH, count = 3)))

        assertTrue(walk.accept(chain(from = hash(99), count = 2)) is AncestorHeaderWalk.Result.Continue)
    }

    private fun assertResolved(result: AncestorHeaderWalk.Result): AncestorHeaderWalk.Result.Resolved {
        assertTrue("expected Resolved, got $result", result is AncestorHeaderWalk.Result.Resolved)
        return result as AncestorHeaderWalk.Result.Resolved
    }

    private fun walk(targetHeight: Int = TARGET_HEIGHT, endHeight: Int = END_HEIGHT) = AncestorHeaderWalk(
        targetHeight = targetHeight,
        merkleBlock = MerkleBlock(header(hash(0), hash(1)), emptyMap()),
        endHash = END_HASH,
        endHeight = endHeight,
        anchors = listOf(ANCHOR_HASH to ANCHOR_HEIGHT)
    )

    /** Links [count] headers onto [from]; the last one carries [endAt] so the walk can resolve. */
    private fun chain(from: ByteArray, count: Int, endAt: ByteArray? = END_HASH): Array<BlockHeader> {
        var previous = from
        return Array(count) { index ->
            val hash = if (index == count - 1 && endAt != null) endAt else hash(index + 2)
            header(hash, previous).also { previous = hash }
        }
    }

    private fun header(hash: ByteArray, previousHash: ByteArray) = BlockHeader(
        version = 1,
        previousBlockHeaderHash = previousHash,
        merkleRoot = byteArrayOf(7),
        timestamp = 0,
        bits = 0x2100ffffL,
        nonce = 0,
        hash = hash
    )

    private fun hash(value: Int) = byteArrayOf(value.toByte())

    private companion object {
        const val ANCHOR_HEIGHT = 3104638
        const val TARGET_HEIGHT = 3104639
        const val END_HEIGHT = 3104641
        val ANCHOR_HASH = byteArrayOf(1)
        val END_HASH = byteArrayOf(50)
    }
}

package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.models.MerkleBlock
import io.horizontalsystems.bitcoincore.storage.BlockHeader

/**
 * Recovers the ancestors between [targetHeight] and a block we already store.
 *
 * The segment is accepted only when a header hashes to [endHash] at the derived height
 * [endHeight]. Every header commits to its parent, so that single match fixes each buffered
 * height and proves the whole segment: no proof-of-work or difficulty check is involved, and
 * the locator the peer answered from is not trusted.
 */
class AncestorHeaderWalk(
    val targetHeight: Int,
    val merkleBlock: MerkleBlock,
    val endHash: ByteArray,
    val endHeight: Int,
    anchors: List<Pair<ByteArray, Int>>
) {

    class VerifiedHeader(val header: BlockHeader, val height: Int)

    sealed class Result {
        object Continue : Result()
        object Abort : Result()
        class Resolved(val headers: List<VerifiedHeader>) : Result()
    }

    private var anchors = anchors
    private val buffer = mutableListOf<VerifiedHeader>()
    private var resolved = false

    val locator: List<ByteArray>
        get() = anchors.map { it.first }

    fun accept(headers: Array<BlockHeader>): Result {
        if (resolved) return Result.Continue
        if (headers.isEmpty()) return Result.Abort

        var previousHash = headers.first().previousBlockHeaderHash
        var height = heightOf(previousHash) ?: return Result.Abort

        for (header in headers) {
            if (!header.previousBlockHeaderHash.contentEquals(previousHash)) return Result.Abort

            height += 1
            if (height > endHeight) return Result.Abort
            if (height >= targetHeight) buffer.add(VerifiedHeader(header, height))

            if (header.hash.contentEquals(endHash)) {
                if (height != endHeight) return Result.Abort

                resolved = true
                return Result.Resolved(buffer.toList())
            }

            previousHash = header.hash
        }

        anchors = listOf(previousHash to height)
        return Result.Continue
    }

    private fun heightOf(hash: ByteArray): Int? =
        anchors.firstOrNull { it.first.contentEquals(hash) }?.second
}

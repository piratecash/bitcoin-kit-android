package io.horizontalsystems.bitcoincore.apisync.legacy

import io.horizontalsystems.bitcoincore.models.BlockHash
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.reactivex.Single
import io.reactivex.SingleEmitter
import java.util.concurrent.CancellationException

class BlockHashDiscoveryBatch(
    private val blockHashScanner: BlockHashScanner,
    private val publicKeyFetcher: IPublicKeyFetcher,
    private val maxHeight: Int,
    private val gapLimit: Int
) {
    fun discoverBlockHashes(): Single<Pair<List<PublicKey>, List<BlockHash>>> {
        return Single.create { emitter ->
            try {
                val result = fetchRecursive(emitter)
                if (!emitter.isDisposed) {
                    emitter.onSuccess(result)
                }
            } catch (e: Exception) {
                if (!emitter.isDisposed) {
                    emitter.onError(e)
                }
            }
        }
    }

    private fun fetchRecursive(
        emitter: SingleEmitter<*>,
        blockHashes: List<BlockHash> = listOf(),
        externalBatchInfo: KeyBlockHashBatchInfo = KeyBlockHashBatchInfo(),
        internalBatchInfo: KeyBlockHashBatchInfo = KeyBlockHashBatchInfo()
    ): Pair<List<PublicKey>, List<BlockHash>> {

        val externalCount = gapLimit - externalBatchInfo.prevCount + externalBatchInfo.prevLastUsedIndex + 1
        val internalCount = gapLimit - internalBatchInfo.prevCount + internalBatchInfo.prevLastUsedIndex + 1

        val externalNewKeys = publicKeyFetcher.publicKeys(externalBatchInfo.startIndex until externalBatchInfo.startIndex + externalCount, true)
        val internalNewKeys = publicKeyFetcher.publicKeys(internalBatchInfo.startIndex until internalBatchInfo.startIndex + internalCount, false)

        val externalPublicKeys = externalBatchInfo.publicKeys + externalNewKeys
        val internalPublicKeys = internalBatchInfo.publicKeys + internalNewKeys

        // Key derivation above is slow and the scan below is blocking, so those are the only two
        // points a disposal can land on. Throwing rather than returning what was collected: a
        // partial result would be stored as a completed restore.
        emitter.throwIfDisposed()
        val fetchResponse = blockHashScanner.getBlockHashes(externalNewKeys, internalNewKeys)
        emitter.throwIfDisposed()

        val resultBlockHashes = blockHashes + fetchResponse.blockHashes.filter { it.height <= maxHeight }

        return when {
            // found all unused keys
            fetchResponse.externalLastUsedIndex < 0 && fetchResponse.internalLastUsedIndex < 0 -> {
                Pair(externalPublicKeys + internalPublicKeys, resultBlockHashes)
            }
            // found some used keys
            else -> {
                val externalBatch = KeyBlockHashBatchInfo(
                    externalPublicKeys,
                    externalCount,
                    fetchResponse.externalLastUsedIndex,
                    externalBatchInfo.startIndex + externalCount
                )
                val internalBatch = KeyBlockHashBatchInfo(
                    internalPublicKeys,
                    internalCount,
                    fetchResponse.internalLastUsedIndex,
                    internalBatchInfo.startIndex + internalCount
                )
                fetchRecursive(emitter, resultBlockHashes, externalBatch, internalBatch)
            }
        }
    }

    private fun SingleEmitter<*>.throwIfDisposed() {
        if (isDisposed) throw CancellationException("block hash discovery terminated")
    }

    private data class KeyBlockHashBatchInfo(
        var publicKeys: List<PublicKey> = listOf(),
        var prevCount: Int = 0,
        var prevLastUsedIndex: Int = -1,
        var startIndex: Int = 0
    )

}

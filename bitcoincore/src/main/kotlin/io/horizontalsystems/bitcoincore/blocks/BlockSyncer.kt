package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.core.IBlockSyncListener
import io.horizontalsystems.bitcoincore.core.IPublicKeyManager
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.BlockHash
import io.horizontalsystems.bitcoincore.models.Checkpoint
import io.horizontalsystems.bitcoincore.models.MerkleBlock
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.horizontalsystems.bitcoincore.transactions.BlockTransactionProcessor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

class BlockSyncer(
    private val storage: IStorage,
    private val blockchain: Blockchain,
    private val transactionProcessor: BlockTransactionProcessor,
    private val publicKeyManager: IPublicKeyManager,
    private val checkpoint: Checkpoint,
    private val state: State = State(),
    private val headerWalkEnabled: Boolean = false
) {
    private val logger = Logger.getLogger("BlockSyncer")

    var listener: IBlockSyncListener? = null

    private val sqliteMaxVariableNumber = 999
    private val maxWalkAnchors = 10

    private val missingAncestors = ConcurrentHashMap<Int, BlockHash>()

    private val headerWalk = AtomicReference<AncestorHeaderWalk?>(null)
    private val headerWalkAbandoned = AtomicBoolean(false)

    val localDownloadedBestBlockHeight: Int
        get() = storage.lastBlock()?.height ?: 0

    val localKnownBestBlockHeight: Int
        get() {
            val blockHashes = storage.getBlockchainBlockHashes()
            val headerHashes = blockHashes.map { it.headerHash }
            val existingBlocksCount = headerHashes.chunked(sqliteMaxVariableNumber).map {
                storage.blocksCount(it)
            }.sum()

            return localDownloadedBestBlockHeight.plus(blockHashes.size - existingBlocksCount)
        }

    fun prepareForDownload() {
        handlePartialBlocks()

        clearPartialBlocks()
        clearBlockHashes() // we need to clear block hashes when "syncPeer" is disconnected
        // Pending recovery belongs to the discarded download context; the failing block re-queues it
        missingAncestors.clear()
        headerWalk.set(null)
        headerWalkAbandoned.set(false)

        blockchain.handleFork()
    }

    fun downloadStarted() {
    }

    fun downloadIterationCompleted() {
        if (state.iterationHasPartialBlocks) {
            handlePartialBlocks()
        }
    }

    fun downloadCompleted() {
        blockchain.handleFork()
    }

    fun downloadFailed() {
        prepareForDownload()
    }

    fun getBlockHashes(limit: Int): List<BlockHash> {
        return missingAncestors.values.toList() + storage.getBlockHashesSortedBySequenceAndHeight(limit)
    }

    fun getOrphanParents(): List<BlockHash> {
        return storage.getOrphanBlocks()
            .distinctBy { it.previousBlockHash.contentHashCode() }
            .map {
                BlockHash(
                    headerHash = it.previousBlockHash,
                    height = 0,
                )
            }
    }

    fun getBlockLocatorHashes(peerLastBlockHeight: Int): List<ByteArray> {
        val result = mutableListOf<ByteArray>()

        storage.getLastBlockchainBlockHash()?.headerHash?.let {
            result.add(it)
        }

        if (result.isEmpty()) {
            storage.getBlocks(
                heightGreaterThan = checkpoint.block.height,
                sortedBy = "height",
                limit = 10
            ).forEach {
                result.add(it.headerHash)
            }
        }

        val lastBlock = storage.getBlock(peerLastBlockHeight)
        if (lastBlock == null) {
            result.add(checkpoint.block.headerHash)
        } else if (!result.contains(lastBlock.headerHash)) {
            result.add(lastBlock.headerHash)
        }

        return result
    }

    fun addBlockHashes(blockHashes: List<ByteArray>) {
        var lastSequence = storage.getLastBlockHash()?.sequence ?: 0

        val existingHashes = storage.getBlockHashHeaderHashes()
        val newBlockHashes =
            blockHashes.filter { existingHashes.none { n -> n.contentEquals(it) } }.map {
                BlockHash(headerHash = it, height = 0, sequence = ++lastSequence)
            }

        storage.addBlockHashes(newBlockHashes)
    }

    fun handleMerkleBlock(merkleBlock: MerkleBlock, maxBlockHeight: Int) {
        handleMerkleBlock(merkleBlock, maxBlockHeight, 0)
    }

    private fun handleMerkleBlock(merkleBlock: MerkleBlock, maxBlockHeight: Int, recursionDepth: Int) {
        val height = merkleBlock.height
        val block = when (height) {
            null -> connectOrQueueMissingAncestor(merkleBlock)
            else -> blockchain.forceAdd(merkleBlock, height)
        }
        val recoveredAncestor = height != null && consumeMissingAncestor(merkleBlock.blockHash, height)

        try {
            transactionProcessor.processReceived(
                transactions = merkleBlock.associatedTransactions,
                block = block,
                skipCheckBloomFilter = state.iterationHasPartialBlocks
            )
        } catch (e: BloomFilterManager.BloomFilterExpired) {
            state.iterationHasPartialBlocks = true
        }

        if (state.iterationHasPartialBlocks) {
            storage.setBlockPartial(block.headerHash)
        } else {
            storage.deleteBlockHash(block.headerHash)
        }

        if (height != null) {
            // A recovered ancestor is a backfill below the tip, not sync progress
            if (!recoveredAncestor) {
                listener?.onBlockForceAdded()
            }
        } else {
            listener?.onCurrentBestBlockHeightUpdate(block.height, maxBlockHeight)
        }

        checkParentsForOrphans(block, maxBlockHeight, recursionDepth)
    }

    private fun connectOrQueueMissingAncestor(merkleBlock: MerkleBlock): Block {
        try {
            return blockchain.connect(merkleBlock)
        } catch (e: BlockValidatorException.NoCheckpointBlock) {
            val missingHeight = e.height ?: throw e
            if (!recoverMissingAncestor(merkleBlock, missingHeight)) throw e

            throw BlockValidatorException.AncestorDownloadQueued(missingHeight)
        }
    }

    /**
     * Starts a header walk where the chain allows one, because it recovers the whole gap in a few
     * round trips instead of one block per download round. Falls back to the merkle-block descent.
     */
    private fun recoverMissingAncestor(merkleBlock: MerkleBlock, missingHeight: Int): Boolean {
        val cursor = findMissingAncestor(merkleBlock, missingHeight) ?: return false

        // A failed walk would restart on every retry of the failing block, and each restart costs
        // the task's idle timeout — far slower than the descent it is meant to accelerate.
        if (headerWalkEnabled && !headerWalkAbandoned.get()) {
            // The failing block stays queued and keeps re-entering here while the walk runs.
            // Replacing the walk would discard its progress and restart it forever.
            if (headerWalk.get() != null) return true

            val anchors = headerWalkAnchors(missingHeight)
            if (anchors.isNotEmpty()) {
                headerWalk.compareAndSet(
                    null,
                    AncestorHeaderWalk(
                        targetHeight = missingHeight,
                        merkleBlock = merkleBlock,
                        endHash = cursor.headerHash,
                        endHeight = cursor.height,
                        anchors = anchors
                    )
                )
                return true
            }
        }

        return queueAncestor(cursor)
    }

    /**
     * Walks down the stored ancestors of the failing block by header hash and returns the lowest
     * one reached. Following hashes rather than heights is what keeps the recovered block on the
     * chain being validated: a retained fork row at the same height is never an ancestor.
     */
    private fun findMissingAncestor(merkleBlock: MerkleBlock, missingHeight: Int): Block? {
        var cursor = storage.getBlock(merkleBlock.header.previousBlockHeaderHash) ?: return null

        while (cursor.height > missingHeight) {
            if (cursor.previousBlockHash.isEmpty()) return cursor

            cursor = storage.getBlock(cursor.previousBlockHash) ?: return cursor
        }

        return null
    }

    private fun enqueueMissingAncestor(merkleBlock: MerkleBlock, missingHeight: Int): Boolean {
        val cursor = findMissingAncestor(merkleBlock, missingHeight) ?: return false

        return queueAncestor(cursor)
    }

    private fun queueAncestor(cursor: Block): Boolean {
        val target = if (cursor.previousBlockHash.isEmpty()) {
            // API placeholder knows no parent: repair it first, the descent resumes next round
            BlockHash(cursor.headerHash, cursor.height)
        } else {
            BlockHash(cursor.previousBlockHash, cursor.height - 1)
        }

        missingAncestors[target.height] = target
        return true
    }

    /**
     * Block locator for the walk: stored blocks below the gap, spaced exponentially, with the
     * checkpoint as the last resort. A wrong anchor cannot mislead the walk — it only shifts the
     * derived heights, which the endpoint-height check then rejects.
     */
    private fun headerWalkAnchors(missingHeight: Int): List<Pair<ByteArray, Int>> {
        val anchors = mutableListOf<Pair<ByteArray, Int>>()
        var height = missingHeight - 1
        var step = 1

        while (height > checkpoint.block.height && anchors.size < maxWalkAnchors) {
            storage.getBlock(height)?.let { anchors.add(it.headerHash to it.height) }
            height -= step
            step *= 2
        }

        if (checkpoint.block.height < missingHeight) {
            anchors.add(checkpoint.block.headerHash to checkpoint.block.height)
        }

        return anchors
    }

    fun pendingHeaderLocator(): List<ByteArray>? = headerWalk.get()?.locator

    fun handleBlockHeaders(headers: Array<BlockHeader>) {
        val walk = headerWalk.get() ?: return

        when (val result = walk.accept(headers)) {
            is AncestorHeaderWalk.Result.Continue -> Unit

            is AncestorHeaderWalk.Result.Abort ->
                if (headerWalk.compareAndSet(walk, null)) {
                    headerWalkAbandoned.set(true)
                    enqueueMissingAncestor(walk.merkleBlock, walk.targetHeight)
                }

            is AncestorHeaderWalk.Result.Resolved ->
                if (headerWalk.compareAndSet(walk, null)) {
                    publishVerifiedAncestors(walk, result.headers)
                }
        }
    }

    private fun publishVerifiedAncestors(
        walk: AncestorHeaderWalk,
        headers: List<AncestorHeaderWalk.VerifiedHeader>
    ) {
        // Endpoint first, target last: an interruption then leaves the target absent, so the
        // validator raises NoCheckpointBlock again and recovery runs from a clean state.
        headers.asReversed().forEach {
            // The proof rests on the endpoint still being committed ancestry, and a concurrent
            // handleFork can drop it mid-loop, so re-read it rather than trust one check.
            if (storage.getBlock(walk.endHash)?.height != walk.endHeight) return

            blockchain.insertVerifiedAncestor(it.header, it.height)
        }
    }

    private fun consumeMissingAncestor(headerHash: ByteArray, height: Int): Boolean {
        val pending = missingAncestors[height] ?: return false
        if (!pending.headerHash.contentEquals(headerHash)) return false

        missingAncestors.remove(height)
        return true
    }

    /***
     * Check if there are any orphan blocks that have parents in the database.
     */
    private fun checkParentsForOrphans(block: Block, maxBlockHeight: Int) {
        checkParentsForOrphans(block, maxBlockHeight, 0)
    }

    /***
     * Check if there are any orphan blocks that have parents in the database.
     * @param recursionDepth Current recursion depth to prevent infinite recursion
     */
    private fun checkParentsForOrphans(block: Block, maxBlockHeight: Int, recursionDepth: Int) {
        // Prevent infinite recursion - limit to reasonable depth
        if (recursionDepth > 100) {
            logger.warning("Maximum recursion depth reached in checkParentsForOrphans, stopping to prevent stack overflow")
            return
        }

        val orphan = storage.getOrphanChild(block.headerHash)
        if (orphan != null && orphan.merkleBlock != null && block.height > 0 && !block.stale) {
            orphan.merkleBlock?.let {
                logger.info("Found orphan block ${it.blockHash.toHexString()} for parent (recursion depth: $recursionDepth)")
                handleMerkleBlock(it, maxBlockHeight, recursionDepth + 1)
                storage.deleteOrphanBlock(orphan)
            }
        }
    }

    fun shouldRequest(blockHash: ByteArray): Boolean {
        return storage.getBlock(blockHash) == null
    }

    private fun clearPartialBlocks() {
        val excludedHashes =
            listOf(checkpoint.block.headerHash) + checkpoint.additionalBlocks.map { it.headerHash }
        val toDelete = storage.getBlockHashHeaderHashes(except = excludedHashes)

        toDelete.chunked(sqliteMaxVariableNumber).forEach {
            val blocksToDelete = storage.getBlocks(hashes = it)
            val partialBlocksToDelete = blocksToDelete.filter { block -> block.partial }

            blockchain.deleteBlocks(partialBlocksToDelete)
        }
    }

    private fun handlePartialBlocks() {
        publicKeyManager.fillGap()
        state.iterationHasPartialBlocks = false
    }

    private fun clearBlockHashes() {
        storage.deleteBlockchainBlockHashes()
    }

    class State(var iterationHasPartialBlocks: Boolean = false)

}

package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.core.IBlockSyncListener
import io.horizontalsystems.bitcoincore.core.IPublicKeyManager
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.BlockHash
import io.horizontalsystems.bitcoincore.models.Checkpoint
import io.horizontalsystems.bitcoincore.models.MerkleBlock
import io.horizontalsystems.bitcoincore.transactions.BlockTransactionProcessor
import java.util.logging.Logger

class BlockSyncer(
    private val storage: IStorage,
    private val blockchain: Blockchain,
    private val transactionProcessor: BlockTransactionProcessor,
    private val publicKeyManager: IPublicKeyManager,
    private val checkpoint: Checkpoint,
    private val state: State = State()
) {
    private val logger = Logger.getLogger("BlockSyncer")

    var listener: IBlockSyncListener? = null

    private val sqliteMaxVariableNumber = 999

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
        return storage.getBlockHashesSortedBySequenceAndHeight(limit)
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

        val block = when (val height = merkleBlock.height) {
            null -> blockchain.connect(merkleBlock)
            else -> blockchain.forceAdd(merkleBlock, height)
        }

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

        if (merkleBlock.height != null) {
            listener?.onBlockForceAdded()
        } else {
            listener?.onCurrentBestBlockHeightUpdate(block.height, maxBlockHeight)
        }

        checkParentsForOrphans(block, maxBlockHeight)
    }

    /***
     * Check if there are any orphan blocks that have parents in the database.
     */
    private fun checkParentsForOrphans(block: Block, maxBlockHeight: Int) {
        val orphan = storage.getOrphanChild(block.headerHash)
        if (orphan != null && orphan.merkleBlock != null) {
            orphan.merkleBlock?.let {
                logger.info("Found orphan block ${it.blockHash.toHexString()} for parent")
                handleMerkleBlock(it, maxBlockHeight)
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

package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.blocks.validators.IBlockValidator
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.MerkleBlock
import io.horizontalsystems.bitcoincore.models.OrphanBlock
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import timber.log.Timber

class Blockchain(
    private val storage: IStorage,
    private val blockValidator: IBlockValidator?,
    private val dataListener: IBlockchainDataListener,
    private val logTag: String
) {
    fun connect(merkleBlock: MerkleBlock): Block {
        val blockInDB = storage.getBlock(merkleBlock.blockHash)
        if (blockInDB != null) {
            Timber.tag(logTag).d("Block already exists in DB: hash=${merkleBlock.blockHash.toHexString()}, height=${blockInDB.height}")

            val header = merkleBlock.header
            var needsUpdate = false

            if (blockInDB.merkleRoot.isEmpty()) {
                blockInDB.merkleRoot = header.merkleRoot.copyOf()
                needsUpdate = true
            }

            if (blockInDB.version != header.version) {
                blockInDB.version = header.version
                needsUpdate = true
            }

            if (!blockInDB.previousBlockHash.contentEquals(header.previousBlockHeaderHash)) {
                blockInDB.previousBlockHash = header.previousBlockHeaderHash.copyOf()
                needsUpdate = true
            }

            if (blockInDB.timestamp != header.timestamp) {
                blockInDB.timestamp = header.timestamp
                needsUpdate = true
            }

            if (blockInDB.bits != header.bits) {
                blockInDB.bits = header.bits
                needsUpdate = true
            }

            if (blockInDB.nonce != header.nonce) {
                blockInDB.nonce = header.nonce
                needsUpdate = true
            }

            if (needsUpdate) {
                storage.updateBlock(blockInDB)
                Timber.tag(logTag).d("Block data refreshed from merkle block: hash=${merkleBlock.blockHash.toHexString()}")
            }

            return blockInDB
        }

        val parentBlock = storage.getBlock(merkleBlock.header.previousBlockHeaderHash)
        if (parentBlock == null) {
            Timber.tag(logTag).i("No parent block found for ${merkleBlock.blockHash.toHexString()}, adding to orphans")
            storage.addOrphanBlock(OrphanBlock(merkleBlock))
            // add to orphans with empty parent
            // Maybe we shouldn't disconnect the peer here since we will request parent block
            throw BlockValidatorException.OrphanBlock(merkleBlock.header.previousBlockHeaderHash)
        }

        val block = Block(merkleBlock, parentBlock)
        try {
            blockValidator?.validate(block, parentBlock)
        } catch (e: BlockValidatorException) {
            Timber.tag(logTag).d("Block validation failed: hash=${merkleBlock.blockHash.toHexString()}, error=${e.message}")
            throw e
        }

        val isFork = checkIfFork(block, parentBlock)
        if (isFork) {
            block.stale = true
            Timber.tag(logTag).d("Block marked as stale (fork): hash=${merkleBlock.blockHash.toHexString()}, height=${block.height}")
        }

        if (block.height % 2016 == 0) {
            storage.deleteBlocksWithoutTransactions(block.height - 2016)
        }

        return addBlockAndNotify(block)
    }

    fun forceAdd(merkleBlock: MerkleBlock, height: Int): Block {
        val blockInDB = storage.getBlock(merkleBlock.blockHash)
        if (blockInDB != null) {
            Timber.tag(logTag).d("Block already exists in DB (forceAdd): hash=${merkleBlock.blockHash.toHexString()}, height=${blockInDB.height}")
            return blockInDB
        }
        Timber.tag(logTag).d("Force adding block: hash=${merkleBlock.blockHash.toHexString()}, height=$height")
        return addBlockAndNotify(Block(merkleBlock.header, height))
    }

    fun insertLastBlock(header: BlockHeader, height: Int) {
        if (storage.getBlock(header.hash) != null) {
            Timber.tag(logTag).d("Last block already exists in DB: hash=${header.hash.toHexString()}, height=$height")
            return
        }

        Timber.tag(logTag).d("Inserting last block: hash=${header.hash.toHexString()}, height=$height")
        addBlockAndNotify(Block(header, height))
    }

    fun handleFork() {
        val firstStaleHeight =
            storage.getBlock(stale = true, sortedHeight = "ASC")?.height ?: return

        val lastNotStaleHeight = storage.getBlock(stale = false, sortedHeight = "DESC")?.height ?: 0

        if (firstStaleHeight <= lastNotStaleHeight) {
            val lastStaleHeight =
                storage.getBlock(stale = true, sortedHeight = "DESC")?.height ?: firstStaleHeight

            if (lastStaleHeight > lastNotStaleHeight) {
                val notStaleBlocks =
                    storage.getBlocks(heightGreaterOrEqualTo = firstStaleHeight, stale = false)
                deleteBlocks(notStaleBlocks)
                storage.unstaleAllBlocks()
            } else {
                val staleBlocks = storage.getBlocks(stale = true)
                deleteBlocks(staleBlocks)
            }
        } else {
            storage.unstaleAllBlocks()
        }
    }

    fun deleteBlocks(blocksToDelete: List<Block>) {
        val deletedTransactionIds = mutableListOf<String>()

        blocksToDelete.forEach { block ->
            deletedTransactionIds.addAll(
                storage.getBlockTransactions(block).map { it.hash.toReversedHex() })
        }

        storage.deleteBlocks(blocksToDelete)

        dataListener.onTransactionsDelete(deletedTransactionIds)
    }

    private fun addBlockAndNotify(block: Block): Block {
        storage.addBlock(block)
//        Timber.tag(logTag).d("Block added successfully: hash=${block.headerHash.toHexString()}, height=${block.height}, stale=${block.stale}")
        dataListener.onBlockInsert(block)
        return block
    }

    private fun checkIfFork(block: Block, parentBlock: Block): Boolean {
        val existingBlockAtHeight = storage.getBlock(block.height)

        if (existingBlockAtHeight != null &&
            existingBlockAtHeight.headerHash.contentEquals(block.headerHash)) {
            return false
        }

        if (existingBlockAtHeight != null &&
            !existingBlockAtHeight.headerHash.contentEquals(block.headerHash)) {

            val currentChainWork = storage.getChainWork(existingBlockAtHeight)
            val newChainWork = storage.getChainWork(block)

            return newChainWork <= currentChainWork
        }

        if (parentBlock.stale) {
            return true
        }

        return false
    }

}

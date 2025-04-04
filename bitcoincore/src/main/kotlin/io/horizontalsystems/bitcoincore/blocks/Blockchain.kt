package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.blocks.validators.IBlockValidator
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.MerkleBlock
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import java.util.logging.Logger

class Blockchain(
    private val storage: IStorage,
    private val blockValidator: IBlockValidator?,
    private val dataListener: IBlockchainDataListener
) {
    private val logger = Logger.getLogger("Blockchain")

    fun connect(merkleBlock: MerkleBlock): Block {
        val blockInDB = storage.getBlock(merkleBlock.blockHash)
        if (blockInDB != null) {
            return blockInDB
        }

        val parentBlock = storage.getBlock(merkleBlock.header.previousBlockHeaderHash)
        if (parentBlock == null) {
            logger.info("No parent block found for ${merkleBlock.blockHash.toHexString()}, adding to orphans...")
            storage.addBlock(Block(merkleBlock, Block()).apply {
                orphan = true
            }) // add to orphans with empty parent
            // Maybe we shouldn't disconnect the peer here since we will request parent block
            throw BlockValidatorException.NoPreviousBlock(merkleBlock.header.previousBlockHeaderHash)
        }

        val block = Block(merkleBlock, parentBlock)
        blockValidator?.validate(block, parentBlock)

        block.stale = true

        if (block.height % 2016 == 0) {
            storage.deleteBlocksWithoutTransactions(block.height - 2016)
        }

        return addBlockAndNotify(block)
    }

    fun forceAdd(merkleBlock: MerkleBlock, height: Int): Block {
        val blockInDB = storage.getBlock(merkleBlock.blockHash)
        if (blockInDB != null) {
            return blockInDB
        }

        return addBlockAndNotify(Block(merkleBlock.header, height))
    }

    fun insertLastBlock(header: BlockHeader, height: Int) {
        if (storage.getBlock(header.hash) != null) {
            return
        }

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
        dataListener.onBlockInsert(block)

        return block
    }
}

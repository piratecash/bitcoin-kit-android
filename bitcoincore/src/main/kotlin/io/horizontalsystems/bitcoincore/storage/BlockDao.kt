package io.horizontalsystems.bitcoincore.storage

import androidx.room.*
import io.horizontalsystems.bitcoincore.models.Block

@Dao
interface BlockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(block: Block)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    fun update(block: Block)

    @Query("UPDATE Block SET partial = 1 WHERE headerHash = :headerHash")
    fun setBlockPartial(headerHash: ByteArray)

    @Query("SELECT * FROM Block WHERE stale = :stale and orphan = 0 ORDER BY height DESC limit 1")
    fun getLast(stale: Boolean): Block?

    @Query("SELECT * FROM Block WHERE stale = :stale and orphan = 0 ORDER BY height ASC limit 1")
    fun getFirst(stale: Boolean): Block?

    @Query("SELECT * FROM Block WHERE orphan = 0 ORDER BY height DESC limit 1")
    fun getLastBlock(): Block?

    @Query("SELECT * FROM Block WHERE hasTransactions = 1 and orphan = 0 ORDER BY height DESC limit 1")
    fun getLastBlockWithTransactions(): Block?

    @Query("SELECT * FROM Block WHERE headerHash and orphan = 0 IN (:hashes)")
    fun getBlocks(hashes: List<ByteArray>): List<Block>

    @Query("SELECT COUNT(headerHash) FROM Block WHERE headerHash and orphan = 0 IN (:hashes)")
    fun getBlocksCount(hashes: List<ByteArray>): Int

    @Query("SELECT * FROM Block WHERE height >= :heightGreaterOrEqualTo AND stale = :stale and orphan = 0")
    fun getBlocks(heightGreaterOrEqualTo: Int, stale: Boolean): List<Block>

    @Query("SELECT * FROM Block WHERE stale = :stale")
    fun getBlocksByStale(stale: Boolean): List<Block>

    @Query("SELECT * FROM Block WHERE height > :heightGreaterThan and orphan = 0 ORDER BY height DESC LIMIT :limit")
    fun getBlocks(heightGreaterThan: Int, limit: Int): List<Block>

    @Query("SELECT * FROM Block WHERE height >= :fromHeight AND height <= :toHeight and orphan = 0 ORDER BY height ASC")
    fun getBlocksChunk(fromHeight: Int, toHeight: Int): List<Block>

    @Query("SELECT * FROM Block WHERE headerHash = :hash limit 1")
    fun getBlockByHash(hash: ByteArray): Block?

    @Query("SELECT * FROM Block WHERE previousBlockHash = :parentHash and orphan = 1 limit 1")
    fun getOrphanChild(parentHash: ByteArray): Block?

    @Query("SELECT * FROM Block WHERE height = :height limit 1")
    fun getBlockByHeight(height: Int): Block?

    @Query("SELECT * FROM Block WHERE orphan = 1 ORDER BY height ASC")
    fun getOrphanBlocks(): List<Block>

    @Query("SELECT * FROM Block WHERE height = :height ORDER by stale DESC limit 1")
    fun getBlockByHeightStalePrioritized(height: Int): Block?

    @Query("SELECT * FROM Block WHERE headerHash = :hash ORDER by stale DESC limit 1")
    fun getBlockByHeightStalePrioritizedByHash(hash: ByteArray): Block?

    @Query("SELECT COUNT(headerHash) FROM Block WHERE orphan = 0")
    fun count(): Int

    @Query("DELETE FROM Block WHERE height < :toHeight AND hasTransactions = 0 and orphan = 0")
    fun deleteBlocksWithoutTransactions(toHeight: Int)

    @Query("UPDATE Block SET stale = 0 WHERE orphan = 1")
    fun unstaleAllBlocks()

    @Delete
    fun delete(block: Block)

    @Delete
    fun deleteAll(blocks: List<Block>)

    @Query("SELECT block_timestamp FROM Block WHERE height >= :from AND height <= :to and orphan = 0 ORDER BY block_timestamp ASC")
    fun getTimestamps(from: Int, to: Int) : List<Long>

}

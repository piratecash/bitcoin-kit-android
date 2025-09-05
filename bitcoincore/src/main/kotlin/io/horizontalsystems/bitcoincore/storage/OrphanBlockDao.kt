package io.horizontalsystems.bitcoincore.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.bitcoincore.models.OrphanBlock

@Dao
interface OrphanBlockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(block: OrphanBlock)

    @Query("SELECT * FROM OrphanBlock WHERE headerHash = :hash limit 1")
    fun getOrphanBlockByHash(hash: ByteArray): OrphanBlock?

    @Query("SELECT * FROM OrphanBlock WHERE previousBlockHash = :parentHash limit 1")
    fun getOrphanChild(parentHash: ByteArray): OrphanBlock?

    @Query("SELECT * FROM OrphanBlock")
    fun getOrphanBlocks(): List<OrphanBlock>

    @Delete
    fun delete(block: OrphanBlock)

    @Delete
    fun deleteAll(blocks: List<OrphanBlock>)
}

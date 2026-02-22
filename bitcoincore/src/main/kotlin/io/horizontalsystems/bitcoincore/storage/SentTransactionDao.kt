package io.horizontalsystems.bitcoincore.storage

import androidx.room.*
import io.horizontalsystems.bitcoincore.models.SentTransaction

@Dao
interface SentTransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(transaction: SentTransaction)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    fun update(transaction: SentTransaction)

    @Query("select * from SentTransaction where hash = :hash limit 1")
    fun getTransaction(hash: ByteArray): SentTransaction?

    @Query("select * from SentTransaction")
    fun getAll(): List<SentTransaction>

    @Delete
    fun delete(transaction: SentTransaction)
}

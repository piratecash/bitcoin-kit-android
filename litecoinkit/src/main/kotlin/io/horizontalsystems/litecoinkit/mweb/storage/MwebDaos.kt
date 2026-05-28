package io.horizontalsystems.litecoinkit.mweb.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MwebAddressDao {
    @Query("SELECT * FROM MwebAddress WHERE `index` = :index")
    fun address(index: Int): MwebAddressEntity?

    @Query("SELECT * FROM MwebAddress ORDER BY `index`")
    fun addresses(): List<MwebAddressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(addresses: List<MwebAddressEntity>)
}

@Dao
interface MwebUtxoDao {
    @Query("SELECT * FROM MwebUtxo ORDER BY height DESC, blockTime DESC")
    fun utxos(): List<MwebUtxoEntity>

    @Query("SELECT * FROM MwebUtxo WHERE spent = 0 ORDER BY height DESC, blockTime DESC")
    fun unspentUtxos(): List<MwebUtxoEntity>

    @Query("SELECT * FROM MwebUtxo WHERE spent = 0 AND height > 0 ORDER BY height DESC, blockTime DESC")
    fun confirmedUnspentUtxos(): List<MwebUtxoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(utxos: List<MwebUtxoEntity>)

    @Query("UPDATE MwebUtxo SET spent = 1 WHERE outputId IN (:outputIds) AND height > 0")
    fun markConfirmedSpent(outputIds: List<String>)

    @Query(
        """
        UPDATE MwebUtxo
        SET height = :height, blockTime = COALESCE(:blockTime, blockTime)
        WHERE outputId IN (:outputIds) AND height = 0
        """
    )
    fun confirmCreated(outputIds: List<String>, height: Int, blockTime: Long?)

    @Query("SELECT outputId FROM MwebUtxo WHERE height = 0")
    fun unconfirmedOutputIds(): List<String>

    @Query("DELETE FROM MwebUtxo WHERE outputId IN (:outputIds) AND height = 0")
    fun deleteUnconfirmed(outputIds: List<String>)
}

@Dao
interface MwebStateDao {
    @Query("SELECT * FROM MwebState WHERE id = 0")
    fun state(): MwebStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(state: MwebStateEntity)
}

@Dao
interface MwebDeliveryCursorDao {
    @Query("SELECT * FROM MwebDeliveryCursor WHERE id = 0")
    fun cursor(): MwebDeliveryCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(cursor: MwebDeliveryCursorEntity)
}

@Dao
interface MwebPendingTransactionDao {
    @Query("SELECT * FROM MwebPendingTransaction ORDER BY timestamp DESC")
    fun pendingTransactions(): List<MwebPendingTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(pendingTransaction: MwebPendingTransactionEntity)

    @Query("DELETE FROM MwebPendingTransaction WHERE timestamp < :timestamp")
    fun deleteOlderThan(timestamp: Long)
}

@Dao
interface MwebOutgoingTransactionDao {
    @Query("SELECT * FROM MwebOutgoingTransaction ORDER BY timestamp DESC, uid DESC")
    fun outgoingTransactions(): List<MwebOutgoingTransactionEntity>

    @Query("SELECT * FROM MwebOutgoingTransaction WHERE confirmedHeight IS NULL ORDER BY timestamp DESC, uid DESC")
    fun pendingOutgoingTransactions(): List<MwebOutgoingTransactionEntity>

    @Query(
        """
        SELECT * FROM MwebOutgoingTransaction
        WHERE confirmedHeight > 0 AND createdOutputIds != '[]'
        ORDER BY timestamp DESC, uid DESC
        """
    )
    fun confirmedOutgoingTransactionsWithCreatedOutputs(): List<MwebOutgoingTransactionEntity>

    @Query(
        """
        SELECT * FROM MwebOutgoingTransaction
        WHERE kind = :kind AND confirmedHeight > 0
        ORDER BY confirmedHeight DESC
        """
    )
    fun confirmedOutgoingTransactions(kind: String): List<MwebOutgoingTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(transaction: MwebOutgoingTransactionEntity)

    @Query(
        """
        UPDATE MwebOutgoingTransaction
        SET confirmedHeight = :height, confirmedTimestamp = :timestamp
        WHERE uid IN (:uids) AND confirmedHeight IS NULL
        """
    )
    fun confirm(uids: List<String>, height: Int, timestamp: Long?)

    @Query(
        """
        UPDATE MwebOutgoingTransaction
        SET canonicalTransactionHash = :canonicalTransactionHash
        WHERE kind = :kind AND confirmedHeight = :height
        """
    )
    fun updateCanonicalHash(kind: String, height: Int, canonicalTransactionHash: String): Int

    @Query("DELETE FROM MwebOutgoingTransaction WHERE uid IN (:uids)")
    fun delete(uids: List<String>)
}

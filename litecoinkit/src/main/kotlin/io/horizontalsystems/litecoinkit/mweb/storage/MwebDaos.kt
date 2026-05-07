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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(utxos: List<MwebUtxoEntity>)

    @Query("UPDATE MwebUtxo SET spent = 1 WHERE outputId IN (:outputIds)")
    fun markSpent(outputIds: List<String>)
}

@Dao
interface MwebStateDao {
    @Query("SELECT * FROM MwebState WHERE id = 0")
    fun state(): MwebStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(state: MwebStateEntity)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(transaction: MwebOutgoingTransactionEntity)

    @Query("DELETE FROM MwebOutgoingTransaction WHERE uid IN (:uids)")
    fun delete(uids: List<String>)
}

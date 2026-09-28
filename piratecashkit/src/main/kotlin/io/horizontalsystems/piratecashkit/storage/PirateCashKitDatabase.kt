package io.horizontalsystems.piratecashkit.storage

import androidx.room.*
import io.horizontalsystems.bitcoincore.core.databaseBuilder
import io.horizontalsystems.piratecashkit.models.InstantTransactionHash
import io.horizontalsystems.piratecashkit.models.InstantTransactionInput
import io.horizontalsystems.piratecashkit.models.Masternode
import io.horizontalsystems.piratecashkit.models.MasternodeListState
import io.horizontalsystems.piratecashkit.models.Quorum
import java.io.File

@Database(version = 10, exportSchema = false, entities = [
    Masternode::class,
    Quorum::class,
    MasternodeListState::class,
    InstantTransactionInput::class,
    InstantTransactionHash::class
])

abstract class PirateCashKitDatabase : RoomDatabase() {
    abstract val instantTransactionHashDao: InstantTransactionHashDao
    abstract val masternodeDao: MasternodeDao
    abstract val quorumDao: QuorumDao
    abstract val masternodeListStateDao: MasternodeListStateDao
    abstract val instantTransactionInputDao: InstantTransactionInputDao

    companion object {
        fun getInstance(dataDir: String, dbName: String): PirateCashKitDatabase {
            return buildDatabase(dataDir, dbName)
        }

        fun getInstance(dataDir: String, dbName: String, databaseKey: ByteArray?): PirateCashKitDatabase {
            return buildDatabase(dataDir, dbName, databaseKey)
        }

        private fun buildDatabase(dataDir: String, dbName: String, databaseKey: ByteArray? = null): PirateCashKitDatabase =
            databaseBuilder<PirateCashKitDatabase>(File(dataDir, dbName).path, databaseKey, allowMainThreadQueries = true)
                .fallbackToDestructiveMigration(dropAllTables = false)
                .build()
    }
}

@Dao
interface InstantTransactionHashDao {
    @Query("SELECT * FROM InstantTransactionHash")
    fun getAll(): List<InstantTransactionHash>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(instantTransactionHash: InstantTransactionHash)

}

@Dao
interface InstantTransactionInputDao {
    @Query("SELECT * FROM InstantTransactionInput WHERE txHash = :txHash")
    fun getByTx(txHash: ByteArray): List<InstantTransactionInput>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(transaction: InstantTransactionInput)

    @Query("DELETE FROM InstantTransactionInput WHERE txHash = :txHash")
    fun deleteByTx(txHash: ByteArray)
}

@Dao
interface MasternodeDao {
    @Insert
    fun insertAll(masternodes: List<Masternode>)

    @Query("SELECT * FROM Masternode")
    fun getAll(): List<Masternode>

    @Query("DELETE FROM Masternode")
    fun clearAll()
}

@Dao
interface QuorumDao {
    @Insert
    fun insertAll(masternodes: List<Quorum>)

    @Query("SELECT * FROM Quorum")
    fun getAll(): List<Quorum>

    @Query("SELECT * FROM Quorum WHERE type = :type")
    fun getByType(type: Int): List<Quorum>

    @Query("DELETE FROM Quorum")
    fun clearAll()
}

@Dao
interface MasternodeListStateDao {
    @Query("SELECT * FROM MasternodeListState LIMIT 1")
    fun getState(): MasternodeListState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setState(state: MasternodeListState)
}

package io.horizontalsystems.dashkit.storage

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import io.horizontalsystems.bitcoincore.core.databaseBuilder
import io.horizontalsystems.dashkit.models.InstantTransactionHash
import io.horizontalsystems.dashkit.models.InstantTransactionInput
import io.horizontalsystems.dashkit.models.Masternode
import io.horizontalsystems.dashkit.models.MasternodeListState
import io.horizontalsystems.dashkit.models.Quorum
import java.io.File

@Database(version = 6, exportSchema = false, entities = [
    Masternode::class,
    Quorum::class,
    MasternodeListState::class,
    InstantTransactionInput::class,
    InstantTransactionHash::class
])

abstract class DashKitDatabase : RoomDatabase() {
    abstract val instantTransactionHashDao: InstantTransactionHashDao
    abstract val masternodeDao: MasternodeDao
    abstract val quorumDao: QuorumDao
    abstract val masternodeListStateDao: MasternodeListStateDao
    abstract val instantTransactionInputDao: InstantTransactionInputDao

    companion object {
        fun getInstance(dataDir: String, dbName: String): DashKitDatabase {
            return buildDatabase(dataDir, dbName)
        }

        fun getInstance(dataDir: String, dbName: String, databaseKey: ByteArray?): DashKitDatabase {
            return buildDatabase(dataDir, dbName, databaseKey)
        }

        private fun buildDatabase(dataDir: String, dbName: String, databaseKey: ByteArray? = null): DashKitDatabase =
            databaseBuilder<DashKitDatabase>(File(dataDir, dbName).path, databaseKey, allowMainThreadQueries = true)
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
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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

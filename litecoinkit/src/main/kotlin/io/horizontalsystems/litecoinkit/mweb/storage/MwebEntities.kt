package io.horizontalsystems.litecoinkit.mweb.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "MwebAddress")
data class MwebAddressEntity(
    @PrimaryKey val index: Int,
    val address: String,
    val used: Boolean,
)

@Entity(
    tableName = "MwebUtxo",
    indices = [Index(value = ["spent"])],
)
data class MwebUtxoEntity(
    @PrimaryKey val outputId: String,
    val address: String,
    val addressIndex: Int,
    val value: Long,
    val height: Int,
    val blockTime: Long,
    val spent: Boolean,
)

@Entity(tableName = "MwebState")
data class MwebStateEntity(
    @PrimaryKey val id: Int = STATE_ID,
    val blockHeaderHeight: Int,
    val mwebHeaderHeight: Int,
    val mwebUtxosHeight: Int,
    val lastSyncedAt: Long,
) {
    companion object {
        const val STATE_ID = 0
    }
}

@Entity(tableName = "MwebPendingTransaction")
data class MwebPendingTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawTransaction: ByteArray,
    val createdOutputIds: List<String>,
    val canonicalTransactionHash: String?,
    val timestamp: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MwebPendingTransactionEntity) return false

        return id == other.id &&
            rawTransaction.contentEquals(other.rawTransaction) &&
            createdOutputIds == other.createdOutputIds &&
            canonicalTransactionHash == other.canonicalTransactionHash &&
            timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + rawTransaction.contentHashCode()
        result = 31 * result + createdOutputIds.hashCode()
        result = 31 * result + (canonicalTransactionHash?.hashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

package io.horizontalsystems.litecoinkit.mweb.storage

import io.horizontalsystems.litecoinkit.mweb.MwebPendingTransaction
import io.horizontalsystems.litecoinkit.mweb.MwebSyncState
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressRecord
import io.horizontalsystems.litecoinkit.mweb.address.MwebAddressStorage

class MwebRoomStorage(
    private val database: MwebDatabase,
) : MwebAddressStorage {
    fun close() {
        database.close()
    }

    override fun address(index: Int): MwebAddressRecord? {
        return database.addressDao.address(index)?.toRecord()
    }

    override fun addresses(): List<MwebAddressRecord> {
        return database.addressDao.addresses().map { it.toRecord() }
    }

    override fun save(records: List<MwebAddressRecord>) {
        database.addressDao.save(records.map { it.toEntity() })
    }

    fun syncState(): MwebSyncState? {
        return database.stateDao.state()?.toSyncState()
    }

    fun saveSyncState(syncState: MwebSyncState) {
        database.stateDao.save(
            MwebStateEntity(
                blockHeaderHeight = syncState.blockHeaderHeight,
                mwebHeaderHeight = syncState.mwebHeaderHeight,
                mwebUtxosHeight = syncState.mwebUtxosHeight,
                lastSyncedAt = System.currentTimeMillis(),
            )
        )
    }

    fun utxos(): List<MwebUtxo> {
        return database.utxoDao.utxos().map { it.toUtxo() }
    }

    fun unspentUtxos(): List<MwebUtxo> {
        return database.utxoDao.unspentUtxos().map { it.toUtxo() }
    }

    fun saveUtxos(utxos: List<MwebUtxo>) {
        database.utxoDao.save(utxos.map { it.toEntity() })
    }

    fun markSpent(outputIds: List<String>) {
        if (outputIds.isEmpty()) return
        database.utxoDao.markSpent(outputIds)
    }

    fun pendingTransactions(): List<MwebPendingTransaction> {
        return database.pendingTransactionDao.pendingTransactions().map { it.toPendingTransaction() }
    }

    fun savePendingTransaction(pendingTransaction: MwebPendingTransaction) {
        database.pendingTransactionDao.save(pendingTransaction.toEntity())
    }

    private fun MwebAddressEntity.toRecord(): MwebAddressRecord {
        return MwebAddressRecord(index = index, address = address, used = used)
    }

    private fun MwebAddressRecord.toEntity(): MwebAddressEntity {
        return MwebAddressEntity(index = index, address = address, used = used)
    }

    private fun MwebStateEntity.toSyncState(): MwebSyncState {
        return MwebSyncState(
            blockHeaderHeight = blockHeaderHeight,
            mwebHeaderHeight = mwebHeaderHeight,
            mwebUtxosHeight = mwebUtxosHeight,
        )
    }

    private fun MwebUtxoEntity.toUtxo(): MwebUtxo {
        return MwebUtxo(
            outputId = outputId,
            address = address,
            addressIndex = addressIndex,
            value = value,
            height = height,
            blockTime = blockTime,
            spent = spent,
        )
    }

    private fun MwebUtxo.toEntity(): MwebUtxoEntity {
        return MwebUtxoEntity(
            outputId = outputId,
            address = address,
            addressIndex = addressIndex,
            value = value,
            height = height,
            blockTime = blockTime,
            spent = spent,
        )
    }

    private fun MwebPendingTransactionEntity.toPendingTransaction(): MwebPendingTransaction {
        return MwebPendingTransaction(
            rawTransaction = rawTransaction,
            createdOutputIds = createdOutputIds,
            canonicalTransactionHash = canonicalTransactionHash,
            timestamp = timestamp,
        )
    }

    private fun MwebPendingTransaction.toEntity(): MwebPendingTransactionEntity {
        return MwebPendingTransactionEntity(
            rawTransaction = rawTransaction,
            createdOutputIds = createdOutputIds,
            canonicalTransactionHash = canonicalTransactionHash,
            timestamp = timestamp,
        )
    }
}

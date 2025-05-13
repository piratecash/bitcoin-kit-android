package io.horizontalsystems.piratecashkit.storage

import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.storage.FullTransactionInfo
import io.horizontalsystems.bitcoincore.storage.Storage
import io.horizontalsystems.piratecashkit.IPirateCashStorage
import io.horizontalsystems.piratecashkit.models.InstantTransactionHash
import io.horizontalsystems.piratecashkit.models.InstantTransactionInput
import io.horizontalsystems.piratecashkit.models.Masternode
import io.horizontalsystems.piratecashkit.models.MasternodeListState
import io.horizontalsystems.piratecashkit.models.Quorum
import io.horizontalsystems.piratecashkit.models.QuorumType

class PirateCashStorage(private val dataStore: PirateCashKitDatabase, private val coreStorage: Storage) : IPirateCashStorage {

    override fun getBlock(blockHash: ByteArray): Block? {
        return coreStorage.getBlock(blockHash)
    }

    override fun instantTransactionHashes(): List<ByteArray> {
        return dataStore.instantTransactionHashDao.getAll().map { it.txHash }
    }

    override fun instantTransactionInputs(txHash: ByteArray): List<InstantTransactionInput> {
        return dataStore.instantTransactionInputDao.getByTx(txHash)
    }

    override fun getTransactionInputs(txHash: ByteArray): List<TransactionInput> {
        return coreStorage.getTransactionInputs(txHash)
    }

    override fun addInstantTransactionInput(instantTransactionInput: InstantTransactionInput) {
        dataStore.instantTransactionInputDao.insert(instantTransactionInput)
    }

    override fun addInstantTransactionHash(txHash: ByteArray) {
        dataStore.instantTransactionHashDao.insert(InstantTransactionHash(txHash))
    }

    override fun removeInstantTransactionInputs(txHash: ByteArray) {
        dataStore.instantTransactionInputDao.deleteByTx(txHash)
    }

    override fun isTransactionExists(txHash: ByteArray): Boolean {
        return coreStorage.isTransactionExists(txHash)
    }

    override fun getQuorumsByType(quorumType: QuorumType): List<Quorum> {
        return dataStore.quorumDao.getByType(quorumType.value)
    }

    fun getFullTransactionInfo(txHash: ByteArray): FullTransactionInfo? {
        return coreStorage.getFullTransactionInfo(txHash)
    }

    override var masternodes: List<Masternode>
        get() = dataStore.masternodeDao.getAll()
        set(value) {
            dataStore.masternodeDao.clearAll()
            dataStore.masternodeDao.insertAll(value)
        }

    override var masternodeListState: MasternodeListState?
        get() = dataStore.masternodeListStateDao.getState()
        set(value) {
            value?.let {
                dataStore.masternodeListStateDao.setState(value)
            }
        }

    override var quorums: List<Quorum>
        get() = dataStore.quorumDao.getAll()
        set(value) {
            dataStore.quorumDao.clearAll()
            dataStore.quorumDao.insertAll(value)
        }
}

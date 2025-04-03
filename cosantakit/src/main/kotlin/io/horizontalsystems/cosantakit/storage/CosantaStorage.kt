package io.horizontalsystems.cosantakit.storage

import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.storage.FullTransactionInfo
import io.horizontalsystems.bitcoincore.storage.Storage
import io.horizontalsystems.cosantakit.ICosantaStorage
import io.horizontalsystems.cosantakit.models.*

class CosantaStorage(private val cosantaStore: CosantaKitDatabase, private val coreStorage: Storage) : ICosantaStorage {

    override fun getBlock(blockHash: ByteArray): Block? {
        return coreStorage.getBlock(blockHash)
    }

    override fun instantTransactionHashes(): List<ByteArray> {
        return cosantaStore.instantTransactionHashDao.getAll().map { it.txHash }
    }

    override fun instantTransactionInputs(txHash: ByteArray): List<InstantTransactionInput> {
        return cosantaStore.instantTransactionInputDao.getByTx(txHash)
    }

    override fun getTransactionInputs(txHash: ByteArray): List<TransactionInput> {
        return coreStorage.getTransactionInputs(txHash)
    }

    override fun addInstantTransactionInput(instantTransactionInput: InstantTransactionInput) {
        cosantaStore.instantTransactionInputDao.insert(instantTransactionInput)
    }

    override fun addInstantTransactionHash(txHash: ByteArray) {
        cosantaStore.instantTransactionHashDao.insert(InstantTransactionHash(txHash))
    }

    override fun removeInstantTransactionInputs(txHash: ByteArray) {
        cosantaStore.instantTransactionInputDao.deleteByTx(txHash)
    }

    override fun isTransactionExists(txHash: ByteArray): Boolean {
        return coreStorage.isTransactionExists(txHash)
    }

    override fun getQuorumsByType(quorumType: QuorumType): List<Quorum> {
        return cosantaStore.quorumDao.getByType(quorumType.value)
    }

    fun getFullTransactionInfo(txHash: ByteArray): FullTransactionInfo? {
        return coreStorage.getFullTransactionInfo(txHash)
    }

    override var masternodes: List<Masternode>
        get() = cosantaStore.masternodeDao.getAll()
        set(value) {
            cosantaStore.masternodeDao.clearAll()
            cosantaStore.masternodeDao.insertAll(value)
        }

    override var masternodeListState: MasternodeListState?
        get() = cosantaStore.masternodeListStateDao.getState()
        set(value) {
            value?.let {
                cosantaStore.masternodeListStateDao.setState(value)
            }
        }

    override var quorums: List<Quorum>
        get() = cosantaStore.quorumDao.getAll()
        set(value) {
            cosantaStore.quorumDao.clearAll()
            cosantaStore.quorumDao.insertAll(value)
        }
}

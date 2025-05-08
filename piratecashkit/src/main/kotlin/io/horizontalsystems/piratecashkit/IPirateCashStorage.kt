package io.horizontalsystems.piratecashkit

import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.piratecashkit.models.InstantTransactionInput
import io.horizontalsystems.piratecashkit.models.Masternode
import io.horizontalsystems.piratecashkit.models.MasternodeListState
import io.horizontalsystems.piratecashkit.models.Quorum
import io.horizontalsystems.piratecashkit.models.QuorumType

interface IPirateCashStorage {
    fun getBlock(blockHash: ByteArray): Block?
    fun instantTransactionHashes(): List<ByteArray>
    fun instantTransactionInputs(txHash: ByteArray): List<InstantTransactionInput>
    fun getTransactionInputs(txHash: ByteArray): List<TransactionInput>
    fun addInstantTransactionInput(instantTransactionInput: InstantTransactionInput)
    fun addInstantTransactionHash(txHash: ByteArray)
    fun removeInstantTransactionInputs(txHash: ByteArray)
    fun isTransactionExists(txHash: ByteArray): Boolean
    fun getQuorumsByType(quorumType: QuorumType): List<Quorum>

    var masternodes: List<Masternode>
    var masternodeListState: MasternodeListState?
    var quorums: List<Quorum>
}

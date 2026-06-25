package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebCreateResult

internal class CreatedMwebTransaction(
    val prepared: PreparedMwebTransaction,
    private val createResult: MwebCreateResult,
    private val signedPublicTransaction: MwebSignedPublicTransaction,
) {
    val rawTransaction: ByteArray
        get() = signedPublicTransaction.rawTransaction
    val outputIds: List<String>
        get() = createResult.outputIds
    val selectedMwebOutputIds: List<String>
        get() = prepared.selectedMwebUtxos.map { it.outputId }
    val publicTransaction: FullTransaction?
        get() = signedPublicTransaction.publicTransaction

    fun setPublicTransactionHash(hash: String) {
        signedPublicTransaction.setPublicTransactionHash(hash)
    }

    fun toSignedRawTransaction(request: MwebSendRequest): MwebSignedRawTransaction {
        val transactionHash = when (request) {
            is MwebSendRequest.PublicToMweb -> MwebRawTransactionHash.canonicalPublicHash(rawTransaction)
            is MwebSendRequest.MwebToPublic,
            is MwebSendRequest.MwebToMweb -> MwebRawTransactionHash.fullRawHash(rawTransaction)
        }
        val canonicalTransactionHash = when (request) {
            is MwebSendRequest.PublicToMweb -> transactionHash
            is MwebSendRequest.MwebToPublic,
            is MwebSendRequest.MwebToMweb -> null
        }
        return MwebSignedRawTransaction(
            transactionHash = transactionHash,
            canonicalTransactionHash = canonicalTransactionHash,
            rawTransaction = rawTransaction,
            outputIds = outputIds,
            fee = prepared.normalFee + prepared.mwebFee,
        )
    }
}

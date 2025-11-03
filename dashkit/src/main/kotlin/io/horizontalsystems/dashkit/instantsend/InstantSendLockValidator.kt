package io.horizontalsystems.dashkit.instantsend

import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.utils.HashUtils
import io.horizontalsystems.dashkit.DashKitErrors
import io.horizontalsystems.dashkit.managers.QuorumListManager
import io.horizontalsystems.dashkit.messages.ISLockMessage
import io.horizontalsystems.dashkit.models.QuorumType

class InstantSendLockValidator(
        private val quorumListManager: QuorumListManager,
        private val bls: BLS
) {

    @Throws
    fun validate(islock: ISLockMessage) {
        val deterministic = islock.cycleHash != null
        val quorum = quorumListManager.getQuorum(QuorumType.LLMQ_50_60, islock.requestId)

        // 02. Make signId data to verify signature
        val signIdPayload = BitcoinOutput()
            .writeByte(quorum.type)
            .write(quorum.quorumHash)
            .write(islock.requestId)
            .write(islock.txHash)

        if (deterministic) {
            signIdPayload.write(checkNotNull(islock.cycleHash))
        }

        val signId = HashUtils.doubleSha256(signIdPayload.toByteArray())

        if (!bls.verifySignature(quorum.quorumPublicKey, islock.sign, signId)) {
            throw DashKitErrors.ISLockValidation.SignatureNotValid()
        }
    }
}

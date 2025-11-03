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
        // 01. Select quorum
        val quorum = quorumListManager.getQuorum(QuorumType.LLMQ_50_60, islock.requestId)

        // 02. Make signId data to verify signature
        // According to DIP-0007 and DIP-0022: SignID = SHA256(quorumHash, requestId, txHash)
        // See: https://github.com/dashpay/dips/blob/master/dip-0007.md
        //      https://github.com/dashpay/dips/blob/master/dip-0022.md
        val signIdPayload = BitcoinOutput()
                .write(quorum.quorumHash)
                .write(islock.requestId)
                .write(islock.txHash)
                .toByteArray()

        val signId = HashUtils.doubleSha256(signIdPayload)

        // 03. Verify signature by BLS
        if (!bls.verifySignature(quorum.quorumPublicKey, islock.sign, signId)) {
            throw DashKitErrors.ISLockValidation.SignatureNotValid()
        }
    }
}

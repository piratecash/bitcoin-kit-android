package io.horizontalsystems.cosantakit.instantsend

import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.utils.HashUtils
import io.horizontalsystems.cosantakit.CosantaKitErrors
import io.horizontalsystems.cosantakit.managers.QuorumListManager
import io.horizontalsystems.cosantakit.messages.ISLockMessage
import io.horizontalsystems.cosantakit.models.QuorumType

class InstantSendLockValidator(
        private val quorumListManager: QuorumListManager,
        private val bls: BLS
) {

    @Throws
    fun validate(islock: ISLockMessage) {
        // 01. Select quorum
        val quorum = quorumListManager.getQuorum(QuorumType.LLMQ_50_60, islock.requestId)

        // 02. Make signId data to verify signature
        val signIdPayload = BitcoinOutput()
                .writeByte(quorum.type)
                .write(quorum.quorumHash)
                .write(islock.requestId)
                .write(islock.txHash)
                .toByteArray()

        val signId = HashUtils.doubleSha256(signIdPayload)

        // 03. Verify signature by BLS
        if (!bls.verifySignature(quorum.quorumPublicKey, islock.sign, signId)) {
            throw CosantaKitErrors.ISLockValidation.SignatureNotValid()
        }
    }
}

package io.horizontalsystems.dashkit.instantsend

import io.horizontalsystems.bitcoincore.core.IHasher
import io.horizontalsystems.dashkit.DashKitErrors
import io.horizontalsystems.dashkit.IDashStorage
import io.horizontalsystems.dashkit.models.Masternode
import io.horizontalsystems.dashlib.BLS
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TransactionLockVoteValidatorTest {

    private val proRegTxHash = ByteArray(32) { 1 }
    private val pubKeyOperator = ByteArray(48) { 2 }
    private val signature = ByteArray(96) { 3 }
    private val hash = ByteArray(32) { 4 }
    private val quorumModifierHash = ByteArray(32) { 5 }

    private val masternode = Masternode().apply {
        proRegTxHash = this@TransactionLockVoteValidatorTest.proRegTxHash
        confirmedHash = ByteArray(32) { 6 }
        pubKeyOperator = this@TransactionLockVoteValidatorTest.pubKeyOperator
        isValid = true
    }

    private val storage = mock<IDashStorage>()
    private val hasher = mock<IHasher>()
    private val bls = mock<BLS>()

    @Test
    fun validate_signatureNotVerified_throwsSignatureNotValid() {
        whenever(storage.masternodes).thenReturn(listOf(masternode))
        whenever(hasher.hash(any())).thenReturn(ByteArray(32) { 7 })
        whenever(bls.verifySignature(pubKeyOperator, signature, hash)).thenReturn(false)

        val validator = TransactionLockVoteValidator(storage, hasher, bls)

        assertThrows(DashKitErrors.LockVoteValidation.SignatureNotValid::class.java) {
            validator.validate(quorumModifierHash, proRegTxHash, signature, hash)
        }
    }
}

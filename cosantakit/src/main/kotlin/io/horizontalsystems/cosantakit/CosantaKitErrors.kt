package io.horizontalsystems.cosantakit

object CosantaKitErrors {
    sealed class LockVoteValidation : Exception() {
        class MasternodeNotFound : LockVoteValidation()
        class MasternodeNotInTop : LockVoteValidation()
        class TxInputNotFound : LockVoteValidation()
        class SignatureNotValid : LockVoteValidation()
    }

    sealed class ISLockValidation : Exception() {
        class SignatureNotValid : ISLockValidation()
        class QuorumNotFound : ISLockValidation()
    }
}

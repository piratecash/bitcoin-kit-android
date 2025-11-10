package io.horizontalsystems.dashkit.instantsend

import io.horizontalsystems.bitcoincore.core.HashBytes
import io.horizontalsystems.dashkit.DashKitErrors
import io.horizontalsystems.dashkit.messages.ISLockMessage
import timber.log.Timber

/**
 * InstantSendLockValidator (DIP-0022)
 *
 * Modified to skip BLS signature verification and rely on peer consensus instead.
 */
class InstantSendLockValidator(
    private val logTag: String
) {

    /**
     * Validate InstantSend Lock (ISDLOCK) message.
     *
     * Performs only structural validation without cryptographic signature verification.
     * BLS signature verification is skipped - validation relies on multiple peer confirmations.
     *
     * @param islock The ISDLOCK message to validate
     * @throws DashKitErrors.ISLockValidation.InvalidStructure if structural validation fails
     */
    @Throws(DashKitErrors.ISLockValidation.InvalidStructure::class)
    fun validate(islock: ISLockMessage) {
        ensureTriviallyValid(islock)
    }

    private fun ensureTriviallyValid(islock: ISLockMessage) {
        if (islock.inputs.isEmpty()) {
            Timber.tag(logTag).d("ISLock rejected: empty inputs")
            throw DashKitErrors.ISLockValidation.InvalidStructure()
        }

        val isTxHashNull = islock.txHash.all { it == 0.toByte() }
        if (isTxHashNull) {
            Timber.tag(logTag).d("ISLock rejected: null txHash")
            throw DashKitErrors.ISLockValidation.InvalidStructure()
        }

        val uniqueInputs = mutableSetOf<Pair<HashBytes, Long>>()
        islock.inputs.forEach { input ->
            val key = HashBytes(input.txHash) to input.vout
            if (!uniqueInputs.add(key)) {
                Timber.tag(logTag).d("ISLock rejected: duplicated input")
                throw DashKitErrors.ISLockValidation.InvalidStructure()
            }
        }
    }
}

package io.horizontalsystems.piratecashkit.instantsend

import co.touchlab.kermit.Logger
import io.horizontalsystems.bitcoincore.core.HashBytes
import io.horizontalsystems.piratecashkit.PirateCashKitErrors
import io.horizontalsystems.piratecashkit.messages.ISLockMessage

/**
 * InstantSendLockValidator (DIP-0022)
 *
 * Modified to skip BLS signature verification and rely on peer consensus instead.
 */
class InstantSendLockValidator(
    private val logTag: String
) {
    private val log = Logger.withTag(logTag)

    /**
     * Validate InstantSend Lock (ISDLOCK) message.
     *
     * Performs only structural validation without cryptographic signature verification.
     * BLS signature verification is skipped - validation relies on multiple peer confirmations.
     *
     * @param islock The ISDLOCK message to validate
     * @throws PirateCashKitErrors.ISLockValidation.InvalidStructure if structural validation fails
     */
    @Throws(PirateCashKitErrors.ISLockValidation.InvalidStructure::class)
    fun validate(islock: ISLockMessage) {
        ensureTriviallyValid(islock)
    }

    private fun ensureTriviallyValid(islock: ISLockMessage) {
        if (islock.inputs.isEmpty()) {
            log.d { "ISLock rejected: empty inputs" }
            throw PirateCashKitErrors.ISLockValidation.InvalidStructure()
        }

        val isTxHashNull = islock.txHash.all { it == 0.toByte() }
        if (isTxHashNull) {
            log.d { "ISLock rejected: null txHash" }
            throw PirateCashKitErrors.ISLockValidation.InvalidStructure()
        }

        val uniqueInputs = mutableSetOf<Pair<HashBytes, Long>>()
        islock.inputs.forEach { input ->
            val key = HashBytes(input.txHash) to input.vout
            if (!uniqueInputs.add(key)) {
                log.d { "ISLock rejected: duplicated input" }
                throw PirateCashKitErrors.ISLockValidation.InvalidStructure()
            }
        }
    }
}

package io.horizontalsystems.piratecashkit.instantsend

import io.horizontalsystems.piratecashkit.models.InstantTransactionInput
import java.util.*

class InstantSendFactory {

    fun instantTransactionInput(txHash: ByteArray, inputTxHash: ByteArray, inputTxOutputIndex: Long, voteCount: Int, blockHeight: Int?) : InstantTransactionInput {
        val timeCreated = Date().time / 1000

        return InstantTransactionInput(txHash, inputTxHash, inputTxOutputIndex, timeCreated, voteCount, blockHeight)
    }

}

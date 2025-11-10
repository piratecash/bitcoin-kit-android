package io.horizontalsystems.cosantakit.instantsend

import io.horizontalsystems.cosantakit.models.InstantTransactionInput
import java.util.*

class InstantSendFactory {

    fun instantTransactionInput(txHash: ByteArray, inputTxHash: ByteArray, inputTxOutputIndex: Long, voteCount: Int, blockHeight: Int?) : InstantTransactionInput {
        val timeCreated = Date().time / 1000

        return InstantTransactionInput(txHash, inputTxHash, inputTxOutputIndex, timeCreated, voteCount, blockHeight)
    }

}

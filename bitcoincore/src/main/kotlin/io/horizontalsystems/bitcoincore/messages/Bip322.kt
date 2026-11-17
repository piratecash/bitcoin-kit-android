package io.horizontalsystems.bitcoincore.messages

import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.transactions.scripts.OP_RETURN
import io.horizontalsystems.bitcoincore.transactions.scripts.OpCodes
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.hdwalletkit.Utils

/** Virtual transactions of the BIP-322 "simple" variant. */
object Bip322 {
    private const val MESSAGE_TAG = "BIP0322-signed-message"
    private const val TO_SPEND_PREVOUT_INDEX = 0xFFFFFFFFL

    fun messageHash(message: String): ByteArray = Utils.taggedHash(MESSAGE_TAG, message.encodeToByteArray())

    fun toSpend(message: String, lockingScript: ByteArray, scriptType: ScriptType): FullTransaction {
        val input = TransactionInput(
            previousOutputTxHash = ByteArray(32),
            previousOutputIndex = TO_SPEND_PREVOUT_INDEX,
            sigScript = OpCodes.push(0) + OpCodes.push(messageHash(message)),
            sequence = 0,
        )
        val output = TransactionOutput(value = 0, index = 0, script = lockingScript, type = scriptType)
        return FullTransaction(Transaction(version = 0, lockTime = 0), listOf(input), listOf(output))
    }

    fun toSign(toSpend: FullTransaction): FullTransaction {
        val input = TransactionInput(
            previousOutputTxHash = toSpend.header.hash,
            previousOutputIndex = 0,
            sequence = 0,
        )
        val output = TransactionOutput(value = 0, index = 0, script = byteArrayOf(OP_RETURN.toByte()))
        return FullTransaction(Transaction(version = 0, lockTime = 0), listOf(input), listOf(output))
    }
}

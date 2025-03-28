package io.horizontalsystems.cosantakit.models

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.InputSerializer
import io.horizontalsystems.bitcoincore.serializers.OutputSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction

internal object CoinbaseTransaction {
    fun deserialize(input: BitcoinInputMarkable): FullTransaction {
        val transaction = Transaction()
        val inputs = mutableListOf<TransactionInput>()
        val outputs = mutableListOf<TransactionOutput>()

        val n32bitVersion = input.readInt()
        transaction.version = (n32bitVersion and 0xFFFF).toInt()
        val nType = (n32bitVersion shr 16).toUShort()

        //  inputs
        val inputCount = input.readVarInt()
        repeat(inputCount.toInt()) {
            inputs.add(InputSerializer.deserialize(input))
        }

        //  outputs
        val outputCount = input.readVarInt()
        for (i in 0 until outputCount) {
            outputs.add(OutputSerializer.deserialize(input, i))
        }

        transaction.lockTime = input.readUnsignedInt()

        var vExtraPayload: ByteArray? = null
        if (transaction.version == 3 && nType != 0.toUShort()) {
            val payloadSize = input.readVarInt()
            vExtraPayload = input.readBytes(payloadSize.toInt())
        }

        return FullTransaction(transaction, inputs, outputs)
    }

}

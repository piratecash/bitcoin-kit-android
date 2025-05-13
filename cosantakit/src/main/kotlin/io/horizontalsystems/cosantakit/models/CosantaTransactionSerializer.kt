package io.horizontalsystems.cosantakit.models

import io.horizontalsystems.bitcoincore.core.DoubleSha256Hasher
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.serializers.InputSerializer
import io.horizontalsystems.bitcoincore.serializers.OutputSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction

internal object CosantaTransactionSerializer {
    private val hasherDoubleSha256 = DoubleSha256Hasher()

    fun deserialize(input: BitcoinInputMarkable): FullTransaction {
        val transaction = Transaction()
        val inputs = mutableListOf<TransactionInput>()
        val outputs = mutableListOf<TransactionOutput>()

        val ver32bit = input.readInt()
        transaction.version = ver32bit and 0xFFFF
        val type =(ver32bit shr 16) and 0xFFFF

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

        if (type != 0) {
            val payloadSize = input.readVarInt()
            val vExtraPayload = input.readBytes(payloadSize.toInt())
        }
        val fullTransaction = FullTransaction(header = transaction, inputs = inputs, outputs = outputs,
            BaseTransactionSerializer())
        transaction.hash = hasherDoubleSha256.hash(serialize(fullTransaction, type.toInt(), byteArrayOf()))

        return fullTransaction
    }

    fun serialize(transaction: FullTransaction, type: Int, extraPayload: ByteArray): ByteArray {
        val header = transaction.header
        val buffer = BitcoinOutput()

        val ver32bit = header.version or (type shl 16)
        buffer.writeInt(ver32bit)

        // inputs
        buffer.writeVarInt(transaction.inputs.size.toLong())
        transaction.inputs.forEach { buffer.write(InputSerializer.serialize(it)) }

        // outputs
        buffer.writeVarInt(transaction.outputs.size.toLong())
        transaction.outputs.forEach { buffer.write(OutputSerializer.serialize(it)) }

        buffer.writeUnsignedInt(header.lockTime)
        if(type != 0) {
            buffer.writeVarInt(extraPayload.size.toLong())
            buffer.write(extraPayload)
        }
        return buffer.toByteArray()
    }
}

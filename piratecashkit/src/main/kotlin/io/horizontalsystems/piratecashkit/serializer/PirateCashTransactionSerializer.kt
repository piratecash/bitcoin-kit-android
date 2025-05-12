package io.horizontalsystems.piratecashkit.serializer

import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.io.BitcoinOutput
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.serializers.InputSerializer
import io.horizontalsystems.bitcoincore.serializers.OutputSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.piratecashkit.models.SpecialTransaction

internal class PirateCashTransactionSerializer : BaseTransactionSerializer() {

    override fun deserialize(input: BitcoinInputMarkable): FullTransaction {
        val transaction = Transaction()
        val inputs = mutableListOf<TransactionInput>()
        val outputs = mutableListOf<TransactionOutput>()

        val ver32bit = input.readInt()
        transaction.version = ver32bit and 0xFFFF
        val type = (ver32bit shr 16) and 0xFFFF

        var nTime: Long = 0
        if (transaction.version == 1 || transaction.version == 2) {
            nTime = input.readUnsignedInt()
        }

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

        var vExtraPayload: ByteArray
        if (transaction.version == 3 && type != 0) {
            val payloadSize = input.readVarInt()
            vExtraPayload = input.readBytes(payloadSize.toInt())
        } else {
            vExtraPayload = byteArrayOf()
        }

        val fullTransaction = SpecialTransaction(
            header = transaction,
            inputs = inputs,
            outputs = outputs,
            extraPayload = vExtraPayload,
            nTime = nTime,
            type = type.toInt(),
            transactionSerializer = this
        )

        return fullTransaction
    }

    override fun serialize(
        transaction: FullTransaction,
        withWitness: Boolean
    ): ByteArray {
        var type = 0
        var nTime = 0L
        var extraPayload: ByteArray? = null

        if (transaction is SpecialTransaction) {
            type = transaction.type
            nTime = transaction.nTime
            extraPayload = transaction.extraPayload
        } else {
            nTime = transaction.header.timestamp
        }

        val header = transaction.header
        val buffer = BitcoinOutput()

        val ver32bit = header.version or (type shl 16)
        buffer.writeInt(ver32bit)

        if (header.version == 1 || header.version == 2) {
            buffer.writeUnsignedInt(nTime)
        }

        // inputs
        buffer.writeVarInt(transaction.inputs.size.toLong())
        transaction.inputs.forEach { buffer.write(InputSerializer.serialize(it)) }

        // outputs
        buffer.writeVarInt(transaction.outputs.size.toLong())
        transaction.outputs.forEach { buffer.write(OutputSerializer.serialize(it)) }

        buffer.writeUnsignedInt(header.lockTime)
        if (header.version == 3 && type != 0) {
            buffer.writeVarInt(extraPayload?.size?.toLong() ?: 0)
            buffer.write(extraPayload)
        }
        return buffer.toByteArray()

    }
}
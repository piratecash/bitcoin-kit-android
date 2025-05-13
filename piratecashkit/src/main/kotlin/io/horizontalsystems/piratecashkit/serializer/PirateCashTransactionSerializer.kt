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

internal class PirateCashTransactionSerializer : BaseTransactionSerializer() {

    override fun deserialize(input: BitcoinInputMarkable): FullTransaction {
        val transaction = Transaction()
        val inputs = mutableListOf<TransactionInput>()
        val outputs = mutableListOf<TransactionOutput>()

        val ver32bit = input.readInt()
        transaction.version = ver32bit and 0xFFFF
        transaction.type = (ver32bit shr 16) and 0xFFFF

        if (transaction.version == 1 || transaction.version == 2) {
            transaction.nTime = input.readUnsignedInt()
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

        if (transaction.version == 3 && transaction.type != 0) {
            val payloadSize = input.readVarInt()
            transaction.extraPayload = input.readBytes(payloadSize.toInt())
        }

        val fullTransaction = FullTransaction(
            header = transaction,
            inputs = inputs,
            outputs = outputs,
            transactionSerializer = this
        )

        return fullTransaction
    }

    override fun serialize(
        transaction: FullTransaction,
        withWitness: Boolean
    ): ByteArray {
        var type = transaction.header.type
        var nTime = transaction.header.nTime
        var extraPayload = transaction.header.extraPayload

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
            buffer.writeVarInt(extraPayload.size.toLong())
            buffer.write(extraPayload)
        }
        return buffer.toByteArray()
    }
}
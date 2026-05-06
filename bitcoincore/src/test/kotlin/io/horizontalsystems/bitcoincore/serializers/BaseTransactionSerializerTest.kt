package io.horizontalsystems.bitcoincore.serializers

import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BaseTransactionSerializerTest {
    private val serializer = BaseTransactionSerializer()

    @Test
    fun serializeDeserialize_legacyTransaction_preservesRawBytes() {
        val rawTransaction =
            "0100000001c997a5e56e104102fa209c6a852dd90660a20b2d9c352423edce25857fcd3704000000004847304402204e45e16932b8af514961a1d3a1a25fdf3f4f7732e9d624c6c61548ab5fb8cd410220181522ec8eca07de4860a4acdd12909d831cc56cbbac4622082221a8768d1d0901ffffffff0200ca9a3b00000000434104ae1a62fe09c5f51b13905f07f06b99a2f7159b2225f374cd378d71302fa28414e7aab37397f554a7df5f142c21c1b7303b8a0626f1baded5c72a704f7e6cd84cac00286bee0000000043410411db93e1dcdb8a016b49840f8c53bc1eb68a382e97b1482ecad7b148a6909a5cb2e0eaddfb84ccf9744464f82e160bfa9b8b64f9d4c03f999b8643f656b412a3ac00000000"

        val transaction = serializer.deserialize(BitcoinInputMarkable(rawTransaction.hexToByteArray()))

        assertEquals(rawTransaction, serializer.serialize(transaction).toHexString())
    }

    @Test
    fun serializeDeserialize_extensionPayload_preservesRawBytes() {
        val payload = byteArrayOf(9, 8, 7)
        val transaction = FullTransaction(
            header = Transaction(version = 2, lockTime = 0).apply {
                extraPayload = payload
            },
            inputs = listOf(
                TransactionInput(
                    previousOutputTxHash = ByteArray(32) { it.toByte() },
                    previousOutputIndex = 1,
                    sequence = 0xfffffffeL,
                )
            ),
            outputs = listOf(
                TransactionOutput(
                    value = 100,
                    index = 0,
                    script = byteArrayOf(0x51),
                    type = ScriptType.UNKNOWN,
                )
            ),
            transactionSerializer = serializer,
        )

        val rawTransaction = serializer.serialize(transaction)
        val deserialized = serializer.deserialize(BitcoinInputMarkable(rawTransaction))

        assertEquals(0, rawTransaction[4].toInt())
        assertEquals(8, rawTransaction[5].toInt())
        assertArrayEquals(payload, deserialized.header.extraPayload)
        assertEquals(rawTransaction.toHexString(), serializer.serialize(deserialized).toHexString())
    }
}

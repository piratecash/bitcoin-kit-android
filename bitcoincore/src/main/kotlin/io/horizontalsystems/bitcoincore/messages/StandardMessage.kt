package io.horizontalsystems.bitcoincore.messages

import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.hdwalletkit.ECException
import io.horizontalsystems.hdwalletkit.ECKey

/** BIP-137 encoding: `header || r || s`, header = 27 + recovery id + script type offset. */
object StandardMessage {
    private const val HEADER_BASE = 27
    private val headerOffsets = mapOf(
        ScriptType.P2PKH to 4,
        ScriptType.P2WPKHSH to 8,
        ScriptType.P2WPKH to 12,
    )

    fun isSupported(scriptType: ScriptType): Boolean = scriptType in headerOffsets

    fun signature(hash: ByteArray, compactSignature: ByteArray, publicKey: ByteArray, scriptType: ScriptType): ByteArray {
        val offset = headerOffsets[scriptType] ?: throw MessageSignError.UnsupportedFormat()
        val recoveryId = try {
            ECKey.recoveryId(hash, compactSignature, publicKey)
        } catch (_: ECException) {
            throw MessageSignError.SignatureMismatch()
        }
        return byteArrayOf((HEADER_BASE + recoveryId + offset).toByte()) + compactSignature
    }
}

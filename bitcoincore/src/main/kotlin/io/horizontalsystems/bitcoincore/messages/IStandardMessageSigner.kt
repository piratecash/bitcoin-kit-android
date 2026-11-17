package io.horizontalsystems.bitcoincore.messages

import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType

/** Optional capability of an input signer: a 65-byte BIP-137 signature of [message]. */
interface IStandardMessageSigner {
    suspend fun signStandardMessage(publicKey: PublicKey, scriptType: ScriptType, message: String): ByteArray
}

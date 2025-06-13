package io.horizontalsystems.bitcoincore.transactions.model

import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType

class DataToSign(
    val publicKey: PublicKey,
    val scriptType: ScriptType,
    val data: ByteArray
)
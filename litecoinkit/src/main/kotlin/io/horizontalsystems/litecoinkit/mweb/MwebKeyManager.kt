package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.hdwalletkit.Curve
import io.horizontalsystems.hdwalletkit.HDKeychain

internal class MwebKeyManager(
    seed: ByteArray,
) {
    private val keychain = HDKeychain(seed.copyOf(), Curve.Secp256K1)

    fun accountKeys(): MwebAccountKeys {
        val scanKey = keychain.getKeyByPath(SCAN_PATH)
        val spendKey = keychain.getKeyByPath(SPEND_PATH)

        return MwebAccountKeys(
            scanSecret = scanKey.privKeyBytes.toSecretBytes(),
            spendSecret = spendKey.privKeyBytes.toSecretBytes(),
            spendPublicKey = spendKey.pubKey,
        )
    }

    private fun ByteArray.toSecretBytes(): ByteArray {
        val normalized = if (size == SERIALIZED_SECRET_SIZE && first() == 0.toByte()) {
            copyOfRange(1, size)
        } else {
            this
        }
        require(normalized.size <= SECRET_SIZE) { "MWEB secret key is too large" }

        return ByteArray(SECRET_SIZE).also { result ->
            normalized.copyInto(
                destination = result,
                destinationOffset = SECRET_SIZE - normalized.size,
            )
        }
    }

    companion object {
        /**
         * mwebd README recommends BIP43 account branch `m/1000'/2'/0'`, with
         * child `0'` used as scan secret and child `1'` as spend secret.
         */
        const val SCAN_PATH = "m/1000'/2'/0'/0'"
        const val SPEND_PATH = "m/1000'/2'/0'/1'"

        private const val SECRET_SIZE = 32
        private const val SERIALIZED_SECRET_SIZE = 33
    }
}

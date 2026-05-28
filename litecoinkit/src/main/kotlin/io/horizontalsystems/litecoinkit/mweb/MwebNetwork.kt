package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.litecoinkit.LitecoinKit

data class MwebNetwork(
    val hrp: String,
    val activationHeight: Int,
)

object MwebNetworkPolicy {
    const val MAINNET_HRP = "ltcmweb"
    const val TESTNET_HRP = "tmweb"
    const val MAINNET_ACTIVATION_HEIGHT = 2_257_920
    const val TESTNET_ACTIVATION_HEIGHT = 0

    fun network(networkType: LitecoinKit.NetworkType): MwebNetwork = when (networkType) {
        LitecoinKit.NetworkType.MainNet -> MwebNetwork(MAINNET_HRP, MAINNET_ACTIVATION_HEIGHT)
        LitecoinKit.NetworkType.TestNet -> MwebNetwork(TESTNET_HRP, TESTNET_ACTIVATION_HEIGHT)
    }
}

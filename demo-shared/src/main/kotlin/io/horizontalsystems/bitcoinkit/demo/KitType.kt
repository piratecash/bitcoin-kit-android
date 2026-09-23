package io.horizontalsystems.bitcoinkit.demo

enum class KitType(val displayName: String) {
    Bitcoin("Bitcoin"),
    BitcoinCash("Bitcoin Cash"),
    ECash("eCash"),
    Litecoin("Litecoin"),
    Dogecoin("Dogecoin"),
    Dash("Dash"),
    Cosanta("Cosanta"),
    PirateCash("PirateCash"),
}

/** What the demo UI may offer for a given kit. */
data class KitCapabilities(
    val hodler: Boolean,
    val mweb: Boolean,
    val purpose: Boolean,
)

val KitType.capabilities: KitCapabilities
    get() = KitCapabilities(
        hodler = this == KitType.Bitcoin,
        mweb = this == KitType.Litecoin,
        purpose = this == KitType.Bitcoin || this == KitType.Litecoin,
    )

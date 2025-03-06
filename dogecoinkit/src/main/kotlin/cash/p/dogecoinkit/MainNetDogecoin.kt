package cash.p.dogecoinkit

import io.horizontalsystems.bitcoincore.network.Network

class MainNetDogecoin : Network() {
    override val protocolVersion: Int = 70015
    override var port: Int = 22556

    override var magic: Long = 0xc0c0c0c0
    override var bip32HeaderPub: Int = 0x02FACAFD   // The 4 byte header that serializes in base58 to "xpub".
    override var bip32HeaderPriv: Int = 0x02FAC398  // The 4 byte header that serializes in base58 to "xprv"
    override var addressVersion: Int = 0x1E
    override var addressSegwitHrp: String = "ltc"
    override var addressScriptVersion: Int = 0x32
    override var coinType: Int = 3
    override val blockchairChainId: String = "dogecoin"

    override val maxBlockSize = 1_000_000
    override val dustRelayTxFee = 3000 // https://github.com/bitcoin/bitcoin/blob/c536dfbcb00fb15963bf5d507b7017c241718bf6/src/policy/policy.h#L50

    override val syncableFromApi = true

    override var dnsSeeds = listOf(
        "multidoge.org",
        "seed.multidoge.org",
        "seed2.multidoge.org"
    )
}

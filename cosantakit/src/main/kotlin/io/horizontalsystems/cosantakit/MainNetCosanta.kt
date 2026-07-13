package io.horizontalsystems.cosantakit

import io.horizontalsystems.bitcoincore.network.Network

class MainNetCosanta : Network() {

    // Intentionally set ABOVE the current Cosanta daemon's PROTOCOL_VERSION (70228,
    // see cosanta-core/src/version.h). Bumped in commit 407858a by the blockchain
    // maintainer to stop the wallet from talking to old nodes — public seed nodes
    // (m1/m2.cosanta.net, dns.cosanta.io, dns.cosa.is, …) are scheduled to be
    // upgraded to >= 70229 after this rollout. Until they are, Peer.validatePeerVersion
    // will reject every currently-published node. This is by design, not a bug.
    override val protocolVersion = 70231
    override val noBloomVersion = 70201

    override var port: Int = 60606

    override var magic: Long = 0x61736f43
    override var bip32HeaderPub: Int = 0x0488B21E   // The 4 byte header that serializes in base58 to "xpub".
    override var bip32HeaderPriv: Int = 0x0488ADE4  // The 4 byte header that serializes in base58 to "xprv"
    override var addressVersion: Int = 28
    override var addressSegwitHrp: String = "bc"
    override var addressScriptVersion: Int = 13
    override var coinType: Int = 770
    override val blockchairChainId: String = "cosanta"

    override val maxBlockSize = 1_000_000
    override val dustRelayTxFee = 1000 // https://github.com/dashpay/dash/blob/master/src/policy/policy.h#L36

    override var dnsSeeds = listOf(
        "m1.cosanta.net",
        "m2.cosanta.net",
        "dns.cosanta.io",
        "dns.cosa.is",
        "65.109.95.133",
    )

    override val logTag = "COSA"
}

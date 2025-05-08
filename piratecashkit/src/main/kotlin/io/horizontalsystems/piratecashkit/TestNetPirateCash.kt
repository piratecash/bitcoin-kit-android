package io.horizontalsystems.piratecashkit

import io.horizontalsystems.bitcoincore.network.Network

class TestNetPirateCash : Network() {

    override val protocolVersion = 70227
    override val noBloomVersion = 70201

    override var port: Int = 63636

    override var magic: Long = 0x61726970
    override var bip32HeaderPub: Int =
        0x0488B21E   // The 4 byte header that serializes in base58 to "xpub".
    override var bip32HeaderPriv: Int =
        0x0488ADE4  // The 4 byte header that serializes in base58 to "xprv"
    override var addressVersion: Int = 75
    override var addressSegwitHrp: String = "bc"
    override var addressScriptVersion: Int = 18
    override var coinType: Int = 1
    override val blockchairChainId: String = "piratecash"

    override val maxBlockSize = 1_000_000
    override val dustRelayTxFee =
        1000 // https://github.com/dashpay/dash/blob/master/src/policy/policy.h#L36

    override var dnsSeeds = listOf(
        "dns1.piratecash.net",
        "dns2.piratecash.net"
    )

    override val logTag = "PirateCash"
}

package io.horizontalsystems.ecash

import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.transactions.scripts.Sighash
import kotlin.experimental.or

class MainNetECash : Network() {

    override var port: Int = 8333

    override var magic: Long = 0xe8f3e1e3L
    override var bip32HeaderPub: Int = 0x0488b21e
    override var bip32HeaderPriv: Int = 0x0488ade4
    override var addressVersion: Int = 0
    override var addressSegwitHrp: String = "ecash"
    override var addressScriptVersion: Int = 5
    override var coinType: Int = 899
    override val blockchairChainId: String = "ecash"

    override val maxBlockSize = 32 * 1024 * 1024
    override val dustRelayTxFee = 1000 // https://github.com/Bitcoin-ABC/bitcoin-abc/blob/master/src/policy/policy.h#L78
    override val sigHashForked = true
    override val sigHashValue = Sighash.FORKID or Sighash.ALL

    // eCash-only seeders from Bitcoin ABC (src/kernel/chainparams.cpp, CMainParams).
    // The previous list was copied from Bitcoin Cash and returned BCH peers, which connect
    // successfully over the shared magic/port but serve the wrong chain.
    // The "x5." prefix filters for peers advertising NODE_NETWORK|NODE_BLOOM, required by SPV.
    override var dnsSeeds = listOf(
        "x5.seed.bitcoinabc.org",   // Bitcoin ABC seeder
        "x5.seeder.fabien.cash",    // Fabien
        "x5.seeder.status.cash"     // status.cash
    )

    // eCash and Bitcoin Cash share the P2P magic and port, so a BCH peer passes the version
    // handshake. This post-fork checkpoint block exists only on the eCash chain; the chain-identity
    // probe rejects any peer that does not have it before it can be used for syncing.
    override val chainIdentityAnchorHash: ByteArray = lastCheckpoint.block.headerHash

    override val logTag = "ECASH"
}

package io.horizontalsystems.bitcoincore.network

import io.horizontalsystems.bitcoincore.models.Checkpoint
import io.horizontalsystems.bitcoincore.transactions.scripts.Sighash
import io.horizontalsystems.bitcoincore.utils.HashUtils

abstract class Network {

    open val protocolVersion = 70014
    open val syncableFromApi = true
    val bloomFilterVersion = 70000
    open val noBloomVersion = 70011
    val networkServices = 0L
    val serviceFullNode = 1L
    val serviceBloomFilter = 4L
    val zeroHashBytes = HashUtils.toBytesAsLE("0000000000000000000000000000000000000000000000000000000000000000")

    abstract val blockchairChainId: String

    open val transactionVersion = 2
    open val usesLastBlockHeightAsLockTime = true
    abstract val maxBlockSize: Int
    abstract val dustRelayTxFee: Int

    abstract var port: Int
    abstract var magic: Long
    abstract var bip32HeaderPub: Int
    abstract var bip32HeaderPriv: Int
    abstract var coinType: Int
    abstract var dnsSeeds: List<String>
    abstract var addressVersion: Int
    abstract var addressSegwitHrp: String
    abstract var addressScriptVersion: Int
    abstract val logTag: String

    open val bip44Checkpoint = Checkpoint("${javaClass.simpleName}-bip44.checkpoint")
    open val lastCheckpoint = Checkpoint("${javaClass.simpleName}.checkpoint")

    // Hash of a block that exists only on this network's chain. When non-null, a peer is asked
    // for headers building on it right after the handshake and is rejected unless its chain
    // contains that block. Used by networks that share P2P magic/port with another chain.
    open val chainIdentityAnchorHash: ByteArray? = null

    open val sigHashForked: Boolean = false
    open val sigHashValue = Sighash.ALL
}

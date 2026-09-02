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

    // BIP324 v2 encrypted transport. Off unless a network's nodes actually implement it: Litecoin
    // and Dogecoin have no v2 transport in any branch, and attempting it there would only cost an
    // extra round trip before falling back to v1 on every connection.
    open val supportsV2Transport: Boolean = false

    /**
     * Whether this network's nodes use Dash's reserved short message ids (128..168) on top of
     * BIP324's own 1..28.
     *
     * Not optional for the Dash family: `v22.1.4-pirate`, the first release shipping v2 enabled by
     * default, emits them with no protocol-version gate, so without the table `mnlistdiff`,
     * `getmnlistd` and `isdlock` would decode as unknown messages and masternode/InstantSend
     * synchronization would silently stop.
     */
    open val usesDashV2ShortIds: Boolean = false

    /**
     * Largest application message accepted over v2, mirroring each node's own
     * `MAX_PROTOCOL_MESSAGE_LENGTH`. Applies to v2 only — the v1 path keeps its existing cap.
     */
    open val maxProtocolMessageLength: Int = 4_000_000

    /**
     * The v2 contents cap: the protocol limit plus the framing the long form adds
     * (1 type byte + 12 command bytes), matching Bitcoin Core's `MAX_CONTENTS_LEN`. Capping the
     * contents at the protocol limit alone would reject a legitimately maximum-sized payload.
     */
    val maxV2ContentsLength: Int
        get() = 1 + 12 + maxProtocolMessageLength
}

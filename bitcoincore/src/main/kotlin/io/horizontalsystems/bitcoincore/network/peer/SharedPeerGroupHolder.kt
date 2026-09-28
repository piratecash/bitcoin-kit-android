package io.horizontalsystems.bitcoincore.network.peer

import io.horizontalsystems.bitcoincore.blocks.BloomFilterLoader
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import java.security.MessageDigest

class SharedPeerGroupHolder(
    val peerGroup: SharedPeerGroup,
    val peerManager: PeerManager,
    val bloomFilterManager: BloomFilterManager,
    val networkMessageParser: NetworkMessageParser,
    val networkMessageSerializer: NetworkMessageSerializer,
    databaseKey: ByteArray? = null,
) {
    private val bloomFilterLoader = BloomFilterLoader(bloomFilterManager, peerManager)
    private val databaseKeyFingerprint = databaseKey?.let(::fingerprint)

    init {
        bloomFilterManager.listener = bloomFilterLoader
        peerGroup.addPeerGroupListener(bloomFilterLoader)
    }

    fun requireDatabaseKey(databaseKey: ByteArray?) {
        val requestedFingerprint = databaseKey?.let(::fingerprint)
        val matches = when {
            databaseKeyFingerprint == null -> requestedFingerprint == null
            requestedFingerprint == null -> false
            else -> MessageDigest.isEqual(databaseKeyFingerprint, requestedFingerprint)
        }
        requestedFingerprint?.fill(0)
        require(matches) { "Shared peer group uses a different database key" }
    }

    private fun fingerprint(key: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(key)
}

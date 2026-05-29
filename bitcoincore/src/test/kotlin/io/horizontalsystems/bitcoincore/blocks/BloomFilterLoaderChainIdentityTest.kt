package io.horizontalsystems.bitcoincore.blocks

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.crypto.BloomFilter
import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import org.junit.Test

/**
 * Behavior pin: BloomFilterLoader.onFilterUpdated must NOT push the wallet's
 * bloom filter to a peer that is still inside the chain-identity probe. The
 * actual guarantee comes from [PeerManager.connected] excluding such peers,
 * but this test fails loudly if a future refactor walks `peers` / a fresh
 * collection that bypasses the filter — re-introducing the exact eCash↔BCH
 * cross-chain leak we just fixed.
 */
class BloomFilterLoaderChainIdentityTest {

    @Test
    fun onFilterUpdated_doesNotSendFilterToPeerAwaitingChainIdentity() {
        val verifiedPeer = mock<Peer> {
            whenever(it.host).thenReturn("good.peer")
            whenever(it.connected).thenReturn(true)
            whenever(it.awaitingChainIdentity).thenReturn(false)
        }
        val probingPeer = mock<Peer> {
            whenever(it.host).thenReturn("wrongchain.peer")
            whenever(it.connected).thenReturn(true)
            whenever(it.awaitingChainIdentity).thenReturn(true)
        }
        val peerManager = PeerManager().apply {
            add(verifiedPeer)
            add(probingPeer)
        }
        val bloomFilterManager = mock<BloomFilterManager>()
        val loader = BloomFilterLoader(bloomFilterManager, peerManager)
        val filter = mock<BloomFilter>()

        loader.onFilterUpdated(filter)

        verify(verifiedPeer).filterLoad(filter)
        verify(probingPeer, never()).filterLoad(any())
    }
}

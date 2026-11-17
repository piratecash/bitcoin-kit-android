package io.horizontalsystems.bitcoinkit.demo

import io.horizontalsystems.bitcoincore.network.Network
import cash.p.dogecoinkit.MainNetDogecoin
import io.horizontalsystems.bitcoincash.MainNetBitcoinCash
import io.horizontalsystems.bitcoinkit.MainNet
import io.horizontalsystems.bitcoinkit.TestNet
import io.horizontalsystems.cosantakit.MainNetCosanta
import io.horizontalsystems.cosantakit.TestNetCosanta
import io.horizontalsystems.dashkit.MainNetDash
import io.horizontalsystems.dashkit.TestNetDash
import io.horizontalsystems.ecash.MainNetECash
import io.horizontalsystems.bitcoincash.TestNetBitcoinCash
import io.horizontalsystems.litecoinkit.MainNetLitecoin
import io.horizontalsystems.litecoinkit.TestNetLitecoin
import cash.p.dogecoinkit.TestNetDogecoin
import io.horizontalsystems.piratecashkit.MainNetPirateCash
import io.horizontalsystems.piratecashkit.TestNetPirateCash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BIP324 enablement matrix, asserted in the one module that can see every kit.
 *
 * `:bitcoincore` defines the flags but cannot reference the coin kits, so without this the matrix
 * would be unverified and a network could silently inherit the wrong default — either attempting v2
 * against nodes that have never implemented it, or missing it where it is available.
 */
class NetworkV2TransportMatrixTest {

    // RegTest is absent on purpose: it ships no bip44 checkpoint resource, so merely constructing
    // it throws — a pre-existing property of that class, unrelated to the transport. It is a local
    // regression network that never talks to real nodes.
    private val v2Networks: List<Network> = listOf(
        MainNet(), TestNet(),
        MainNetDash(), TestNetDash(),
        MainNetPirateCash(), TestNetPirateCash(),
        MainNetCosanta(), TestNetCosanta(),
    )

    @Test
    fun networksWithNodeSupport_enableV2Transport() {
        v2Networks.forEach {
            assertTrue("${it.javaClass.simpleName} must attempt v2", it.supportsV2Transport)
        }
    }

    /**
     * Litecoin and Dogecoin have no `V2Transport` in any branch, and Bitcoin Cash / eCash never
     * merged BIP324 either. Attempting v2 there would only cost a failed handshake and a reconnect
     * on every single connection. Every one of their networks is listed, so a stray override in any
     * of them fails here rather than in production.
     */
    @Test
    fun networksWithoutNodeSupport_stayOnV1() {
        listOf(
            MainNetLitecoin(), TestNetLitecoin(),
            MainNetDogecoin(), TestNetDogecoin(),
            MainNetBitcoinCash(), TestNetBitcoinCash(),
            MainNetECash(),
        ).forEach {
            assertFalse("${it.javaClass.simpleName} must not attempt v2", it.supportsV2Transport)
            assertFalse("${it.javaClass.simpleName} must not use Dash short ids", it.usesDashV2ShortIds)
        }
    }

    /**
     * The Dash-derived chains emit short message ids from their own 128..168 namespace, and the
     * deployed releases do so without a protocol-version gate — so decoding them is mandatory
     * there and meaningless for Bitcoin.
     */
    @Test
    fun dashFamilyNetworks_useTheDashShortIdNamespace() {
        listOf(MainNetDash(), TestNetDash(), MainNetPirateCash(), TestNetPirateCash(), MainNetCosanta(), TestNetCosanta())
            .forEach { assertTrue("${it.javaClass.simpleName} needs the Dash short ids", it.usesDashV2ShortIds) }

        listOf(MainNet(), TestNet())
            .forEach { assertFalse("${it.javaClass.simpleName} has no Dash namespace", it.usesDashV2ShortIds) }
    }

    /** Each family caps v2 messages at its own node's MAX_PROTOCOL_MESSAGE_LENGTH. */
    @Test
    fun protocolMessageLimits_matchTheNodeImplementations() {
        assertEquals(4_000_000, MainNet().maxProtocolMessageLength)
        assertEquals(3 * 1024 * 1024, MainNetPirateCash().maxProtocolMessageLength)
        assertEquals(3 * 1024 * 1024, MainNetDash().maxProtocolMessageLength)
        assertEquals(3 * 1024 * 1024, MainNetCosanta().maxProtocolMessageLength)

        // The contents cap adds the long-form framing (1 type byte + 12 command bytes), mirroring
        // Bitcoin Core's MAX_CONTENTS_LEN; capping contents at the protocol limit alone would
        // reject a legitimately maximum-sized payload.
        assertEquals(13 + 4_000_000, MainNet().maxV2ContentsLength)
    }
}

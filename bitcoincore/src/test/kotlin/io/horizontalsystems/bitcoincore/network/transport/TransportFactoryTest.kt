package io.horizontalsystems.bitcoincore.network.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * The single most likely silent failure in this feature.
 *
 * BIP324 mixes the network's 4 message-start bytes into the HKDF salt. Get their order wrong and
 * both peers derive different keys, producing a stream neither can decrypt — with no error message
 * pointing anywhere near the cause. `Network.magic` is a Long that the v1 serializer writes
 * little-endian, so the salt must use the same order.
 */
class TransportFactoryTest {

    @Test
    fun magicBytes_matchTheWireOrderOfTheV1Envelope() {
        // Bitcoin mainnet: the frame on the wire starts f9 be b4 d9.
        assertArrayEquals(
            byteArrayOf(0xF9.toByte(), 0xBE.toByte(), 0xB4.toByte(), 0xD9.toByte()),
            DefaultTransportFactory.magicBytes(0xD9B4BEF9L)
        )

        // PirateCash: 0x706d7570 on the wire is 70 75 6d 70 — "pump" in ASCII.
        assertArrayEquals(
            byteArrayOf(0x70, 0x75, 0x6D, 0x70),
            DefaultTransportFactory.magicBytes(0x706D7570L)
        )
    }

}

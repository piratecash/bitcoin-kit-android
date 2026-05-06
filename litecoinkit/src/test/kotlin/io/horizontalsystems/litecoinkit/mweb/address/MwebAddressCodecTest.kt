package io.horizontalsystems.litecoinkit.mweb.address

import io.horizontalsystems.bitcoincore.exceptions.AddressFormatException
import io.horizontalsystems.litecoinkit.LitecoinKit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MwebAddressCodecTest {
    private val mainnetCodec = MwebAddressCodec(LitecoinKit.NetworkType.MainNet)
    private val testnetCodec = MwebAddressCodec(LitecoinKit.NetworkType.TestNet)
    private val scanPublicKey = ByteArray(33) { (it + 1).toByte() }
    private val spendPublicKey = ByteArray(33) { (it + 34).toByte() }

    @Test
    fun decode_mainnetAddress_returnsKeys() {
        val address = mainnetCodec.encode(scanPublicKey, spendPublicKey)

        val decoded = mainnetCodec.decode(address)

        assertTrue(address.startsWith("ltcmweb1"))
        assertTrue(address.length > 90)
        assertEquals(address, decoded.stringValue)
        assertArrayEquals(scanPublicKey, decoded.scanPublicKey)
        assertArrayEquals(spendPublicKey, decoded.spendPublicKey)
    }

    @Test
    fun isValid_wrongNetwork_returnsFalse() {
        val testnetAddress = testnetCodec.encode(scanPublicKey, spendPublicKey)

        assertFalse(mainnetCodec.isValid(testnetAddress))
    }

    @Test(expected = AddressFormatException::class)
    fun validate_typoPrefix_throws() {
        val address = mainnetCodec.encode(scanPublicKey, spendPublicKey)
            .replace("ltcmweb", "ltcwmeb")

        mainnetCodec.validate(address)
    }

    @Test(expected = AddressFormatException::class)
    fun decode_shortPayload_throws() {
        val address = mainnetCodec.encode(ByteArray(33), ByteArray(33))
            .dropLast(10)

        mainnetCodec.decode(address)
    }
}

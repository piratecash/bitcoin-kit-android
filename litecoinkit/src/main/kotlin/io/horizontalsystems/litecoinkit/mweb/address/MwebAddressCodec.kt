package io.horizontalsystems.litecoinkit.mweb.address

import io.horizontalsystems.bitcoincore.crypto.Bech32.Encoding
import io.horizontalsystems.bitcoincore.crypto.Bech32Segwit
import io.horizontalsystems.bitcoincore.exceptions.AddressFormatException
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.MwebNetworkPolicy
import java.util.Locale

class MwebAddressCodec(
    networkType: LitecoinKit.NetworkType,
) {
    private val network = MwebNetworkPolicy.network(networkType)

    fun validate(address: String) {
        decode(address)
    }

    fun isValid(address: String): Boolean = try {
        validate(address)
        true
    } catch (_: AddressFormatException) {
        false
    }

    fun decode(address: String): MwebAddress {
        val decoded = MwebBech32.decode(address)
        if (decoded.hrp != network.hrp) {
            throw AddressFormatException("Address HRP ${decoded.hrp} is not correct")
        }
        if (decoded.encoding != Encoding.BECH32) {
            throw AddressFormatException("MWEB address must use Bech32")
        }
        if (decoded.data.isEmpty() || decoded.data[0].toInt() != 0) {
            throw AddressFormatException("Invalid MWEB address version")
        }

        val payload = Bech32Segwit.convertBits(decoded.data, 1, decoded.data.size - 1, 5, 8, false)
        if (payload.size != MWEB_ADDRESS_PAYLOAD_SIZE) {
            throw AddressFormatException("Invalid MWEB address payload size")
        }

        return MwebAddress(
            stringValue = MwebBech32.encode(network.hrp, Encoding.BECH32, decoded.data),
            scanPublicKey = payload.copyOfRange(0, MWEB_KEY_SIZE),
            spendPublicKey = payload.copyOfRange(MWEB_KEY_SIZE, MWEB_ADDRESS_PAYLOAD_SIZE),
        )
    }

    fun encode(scanPublicKey: ByteArray, spendPublicKey: ByteArray): String {
        require(scanPublicKey.size == MWEB_KEY_SIZE) { "Scan public key must be 33 bytes" }
        require(spendPublicKey.size == MWEB_KEY_SIZE) { "Spend public key must be 33 bytes" }

        val payload = scanPublicKey + spendPublicKey
        val converted = Bech32Segwit.convertBits(payload, 0, payload.size, 8, 5, true)
        return MwebBech32.encode(network.hrp, Encoding.BECH32, byteArrayOf(0) + converted)
    }

    private companion object {
        const val MWEB_KEY_SIZE = 33
        const val MWEB_ADDRESS_PAYLOAD_SIZE = MWEB_KEY_SIZE * 2
    }
}

class MwebAddress(
    val stringValue: String,
    scanPublicKey: ByteArray,
    spendPublicKey: ByteArray,
) {
    val scanPublicKey: ByteArray = scanPublicKey.copyOf()
    val spendPublicKey: ByteArray = spendPublicKey.copyOf()
}

private class MwebBech32Data(
    val hrp: String,
    val data: ByteArray,
    val encoding: Encoding,
)

private object MwebBech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun encode(hrp: String, encoding: Encoding, values: ByteArray): String {
        return Bech32Segwit.encode(hrp, encoding, values)
    }

    fun decode(address: String): MwebBech32Data {
        var lower = false
        var upper = false
        if (address.length < MIN_ADDRESS_LENGTH) {
            throw AddressFormatException("Input too short")
        }

        address.forEachIndexed { index, char ->
            if (char.code < 33 || char.code > 126) {
                throw AddressFormatException("Characters out of range")
            }
            lower = lower || char in 'a'..'z'
            upper = upper || char in 'A'..'Z'
            if (char.code >= CHARSET_REV_SIZE) {
                throw AddressFormatException("Unexpected character at pos $index")
            }
        }
        if (lower && upper) {
            throw AddressFormatException("Cannot mix upper and lower cases")
        }

        val separatorIndex = address.lastIndexOf('1')
        if (separatorIndex < 1) {
            throw AddressFormatException("Missing human-readable part")
        }
        if (separatorIndex + CHECKSUM_LENGTH + 1 > address.length) {
            throw AddressFormatException("Data part too short")
        }

        val values = ByteArray(address.length - separatorIndex - 1)
        for (index in values.indices) {
            val char = address[index + separatorIndex + 1]
            val value = CHARSET.indexOf(char.lowercaseChar())
            if (value < 0) {
                throw AddressFormatException("Characters out of range")
            }
            values[index] = value.toByte()
        }

        val hrp = address.substring(0, separatorIndex).lowercase(Locale.ROOT)
        val encoding = verifyChecksum(hrp, values)
            ?: throw AddressFormatException("Invalid checksum")
        return MwebBech32Data(hrp, values.copyOfRange(0, values.size - CHECKSUM_LENGTH), encoding)
    }

    private fun verifyChecksum(hrp: String, values: ByteArray): Encoding? {
        val expanded = expandHrp(hrp)
        val combined = expanded + values
        val checksum = polymod(combined)
        return when (checksum) {
            BECH32_CHECKSUM -> Encoding.BECH32
            BECH32M_CHECKSUM -> Encoding.BECH32M
            else -> null
        }
    }

    private fun expandHrp(hrp: String): ByteArray {
        val expanded = ByteArray(hrp.length * 2 + 1)
        hrp.forEachIndexed { index, char ->
            val value = char.code and 0x7f
            expanded[index] = ((value ushr 5) and 0x07).toByte()
            expanded[index + hrp.length + 1] = (value and 0x1f).toByte()
        }
        expanded[hrp.length] = 0
        return expanded
    }

    private fun polymod(values: ByteArray): Int {
        var checksum = 1
        for (value in values) {
            val top = (checksum ushr 25) and 0xff
            checksum = ((checksum and 0x1ffffff) shl 5) xor (value.toInt() and 0xff)
            if ((top and 1) != 0) checksum = checksum xor 0x3b6a57b2
            if ((top and 2) != 0) checksum = checksum xor 0x26508e6d
            if ((top and 4) != 0) checksum = checksum xor 0x1ea119fa
            if ((top and 8) != 0) checksum = checksum xor 0x3d4233dd
            if ((top and 16) != 0) checksum = checksum xor 0x2a1462b3
        }
        return checksum
    }

    private const val MIN_ADDRESS_LENGTH = 8
    private const val CHECKSUM_LENGTH = 6
    private const val CHARSET_REV_SIZE = 128
    private const val BECH32_CHECKSUM = 1
    private const val BECH32M_CHECKSUM = 0x2bc830a3
}

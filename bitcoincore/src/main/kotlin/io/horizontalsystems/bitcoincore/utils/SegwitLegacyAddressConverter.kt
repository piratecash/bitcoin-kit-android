package io.horizontalsystems.bitcoincore.utils

import io.horizontalsystems.bitcoincore.crypto.Bech32.Encoding
import io.horizontalsystems.bitcoincore.crypto.Bech32Segwit
import io.horizontalsystems.bitcoincore.exceptions.AddressFormatException
import io.horizontalsystems.bitcoincore.models.Address
import io.horizontalsystems.bitcoincore.models.AddressType
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.SegWitV0Address
import io.horizontalsystems.bitcoincore.models.TaprootAddress
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType

/***
 * To support blockchair address forms for BIP86
 * It uses different checksum approach
 */
class SegwitLegacyAddressConverter(addressSegwitHrp: String) :
    Bech32AddressConverter(addressSegwitHrp) {
    override fun convert(addressString: String): Address {
        val decoded = Bech32Segwit.decode(addressString)
        if (decoded.hrp != hrp) {
            throw AddressFormatException("Address HRP ${decoded.hrp} is not correct")
        }
        val data = decoded.data
        val stringValue = Bech32Segwit.encode(hrp, decoded.encoding, data)
        val program = Bech32Segwit.convertBits(data, 1, data.size - 1, 5, 8, false)

        return when (val version = data[0].toInt()) {
            0 -> {
                val type = when (program.size) {
                    20 -> AddressType.PubKeyHash
                    32 -> AddressType.ScriptHash
                    else -> throw AddressFormatException("Unknown address type")
                }
                SegWitV0Address(stringValue, program, type)
            }

            1 -> {
                TaprootAddress(stringValue, program, version)
            }

            else -> throw AddressFormatException("Unknown address type")
        }
    }

    override fun convert(lockingScriptPayload: ByteArray, scriptType: ScriptType): Address {
        val witnessScript =
            Bech32Segwit.convertBits(lockingScriptPayload, 0, lockingScriptPayload.size, 8, 5, true)

        return when (scriptType) {
            ScriptType.P2WPKH -> {
                val addressString = Bech32Segwit.encode(
                    hrp,
                    Encoding.BECH32,
                    byteArrayOf(0.toByte()) + witnessScript
                )
                SegWitV0Address(addressString, lockingScriptPayload, AddressType.PubKeyHash)
            }

            ScriptType.P2WSH -> {
                val addressString = Bech32Segwit.encode(
                    hrp,
                    Encoding.BECH32,
                    byteArrayOf(0.toByte()) + witnessScript
                )
                SegWitV0Address(addressString, lockingScriptPayload, AddressType.ScriptHash)
            }

            ScriptType.P2TR -> {
                // Используем Bech32 вместо Bech32m для Taproot-адресов
                val addressString = Bech32Segwit.encode(
                    hrp,
                    Encoding.BECH32,
                    byteArrayOf(1.toByte()) + witnessScript
                )
                TaprootAddress(addressString, lockingScriptPayload, 1)
            }

            else -> throw AddressFormatException("Unknown Address Type")
        }
    }

    override fun convert(publicKey: PublicKey, scriptType: ScriptType) = when (scriptType) {
        ScriptType.P2WPKH, ScriptType.P2WSH -> {
            convert(publicKey.publicKeyHash, scriptType)
        }

        ScriptType.P2TR -> {
            convert(publicKey.convertedForP2TR, scriptType)
        }

        else -> throw AddressFormatException("Unknown Address Type")
    }
}
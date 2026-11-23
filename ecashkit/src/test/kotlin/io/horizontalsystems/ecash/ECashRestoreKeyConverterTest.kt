package io.horizontalsystems.ecash

import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.utils.CashAddressConverter
import io.horizontalsystems.hdwalletkit.HDWallet
import org.junit.Assert.assertEquals
import org.junit.Test

// Blockchair's eCash endpoint rejects the whole request with 400 when any key is a raw hex hash.
class ECashRestoreKeyConverterTest {

    @Test
    fun keysForApiRestore_p2pkhKey_returnsOnlyCashAddress() {
        val converter = ECashRestoreKeyConverter(CashAddressConverter("ecash"), HDWallet.Purpose.BIP44)
        val key = PublicKey().apply {
            publicKeyHash = "e4de5d630c5cacd7af96418a8f35c411c8ff3c06".hexToByteArray()
        }

        assertEquals(listOf("ecash:qrjduhtrp3w2e4a0jeqc4re4csgu3leuqc8ylgggrr"), converter.keysForApiRestore(key))
    }
}

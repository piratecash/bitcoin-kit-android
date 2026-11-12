package io.horizontalsystems.bitcoincore.network.transport.v2.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Shared across this package's tests (internal, not private, so the other Bip324/HKDF/EllSwift
// test classes below in this module can reuse them without duplicating the hex codec).
internal fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { i ->
    ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

class ChaCha20Test {

    private data class ChaCha20Vector(val keyHex: String, val nonceHex: String, val counter: Int, val outputHex: String)

    // RFC7539/8439 ChaCha20 block function vectors, ported from Bitcoin Core's
    // test_framework/crypto/chacha20.py: CHACHA20_TESTS.
    private val CHACHA20_VECTORS = listOf(
        ChaCha20Vector("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "000000090000004a00000000", 1, "10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4ed2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e"),
        ChaCha20Vector("0000000000000000000000000000000000000000000000000000000000000000", "000000000000000000000000", 0, "76b8e0ada0f13d90405d6ae55386bd28bdd219b8a08ded1aa836efcc8b770dc7da41597c5157488d7724e03fb8d84a376a43b8f41518a11cc387b669b2ee6586"),
        ChaCha20Vector("0000000000000000000000000000000000000000000000000000000000000000", "000000000000000000000000", 1, "9f07e7be5551387a98ba977c732d080dcb0f29a048e3656912c6533e32ee7aed29b721769ce64e43d57133b074d839d531ed1f28510afb45ace10a1f4b794d6f"),
        ChaCha20Vector("0000000000000000000000000000000000000000000000000000000000000001", "000000000000000000000000", 1, "3aeb5224ecf849929b9d828db1ced4dd832025e8018b8160b82284f3c949aa5a8eca00bbb4a73bdad192b5c42f73f2fd4e273644c8b36125a64addeb006c13a0"),
        ChaCha20Vector("00ff000000000000000000000000000000000000000000000000000000000000", "000000000000000000000000", 2, "72d54dfbf12ec44b362692df94137f328fea8da73990265ec1bbbea1ae9af0ca13b25aa26cb4a648cb9b9d1be65b2c0924a66c54d545ec1b7374f4872e99f096"),
        ChaCha20Vector("0000000000000000000000000000000000000000000000000000000000000000", "000000000000000000000002", 0, "c2c64d378cd536374ae204b9ef933fcd1a8b2288b3dfa49672ab765b54ee27c78a970e0e955c14f3a88e741b97c286f75f8fc299e8148362fa198a39531bed6d"),
        ChaCha20Vector("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "000000000000004a00000000", 1, "224f51f3401bd9e12fde276fb8631ded8c131f823d2c06e27e4fcaec9ef3cf788a3b0aa372600a92b57974cded2b9334794cba40c63e34cdea212c4cf07d41b7"),
        ChaCha20Vector("0000000000000000000000000000000000000000000000000000000000000001", "000000000000000000000000", 0, "4540f05a9f1fb296d7736e7b208e3c96eb4fe1834688d2604f450952ed432d41bbe2a0b6ea7566d2a5d1e7e20d42af2c53d792b1c43fea817e9ad275ae546963"),
        ChaCha20Vector("0000000000000000000000000000000000000000000000000000000000000000", "000000000100000000000000", 0, "ef3fdfd6c61578fbf5cf35bd3dd33b8009631634d21e42ac33960bd138e50d32111e4caf237ee53ca8ad6426194a88545ddc497a0b466e7d6bbdb0041b2f586b"),
        ChaCha20Vector("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "000000000001020304050607", 0, "f798a189f195e66982105ffb640bb7757f579da31602fc93ec01ac56f85ac3c134a4547b733b46413042c9440049176905d3be59ea1c53f15916155c2be8241a"),
    )

    @Test
    fun block_rfc8439Vectors_matchesReference() {
        for (v in CHACHA20_VECTORS) {
            val actual = ChaCha20.block(v.keyHex.hexToBytes(), v.nonceHex.hexToBytes(), v.counter)
            assertEquals(v.outputHex, actual.toHex())
        }
    }

    private data class FSChaCha20Vector(val plainHex: String, val keyHex: String, val rekeyInterval: Int, val cipherAfterRotationHex: String)

    // FSChaCha20 vectors, ported from Bitcoin Core's test_framework/crypto/chacha20.py: FSCHACHA20_TESTS.
    // Each vector performs [rekeyInterval] crypt() calls (crossing exactly one rekey boundary),
    // then checks the ciphertext of the following call, which is encrypted under the rotated key.
    private val FSCHACHA20_VECTORS = listOf(
        FSChaCha20Vector("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "0000000000000000000000000000000000000000000000000000000000000000", 256, "a93df4ef03011f3db95f60d996e1785df5de38fc39bfcb663a47bb5561928349"),
        FSChaCha20Vector("01", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", 5, "ea"),
        FSChaCha20Vector("e93fdb5c762804b9a706816aca31e35b11d2aa3080108ef46a5b1f1508819c0a", "8ec4c3ccdaea336bdeb245636970be01266509b33f3d2642504eaf412206207a", 4096, "8bfaa4eacff308fdb4a94a5ff25bd9d0c1f84b77f81239f67ff39d6e1ac280c9"),
    )

    @Test
    fun crypt_afterRekeyBoundary_matchesReference() {
        for (v in FSCHACHA20_VECTORS) {
            val plaintext = v.plainHex.hexToBytes()
            val cipher = FSChaCha20(v.keyHex.hexToBytes(), v.rekeyInterval)
            repeat(v.rekeyInterval) { cipher.crypt(plaintext) }
            val actual = cipher.crypt(plaintext)
            assertEquals(v.cipherAfterRotationHex, actual.toHex())
        }
    }

    // The constructor must defensively copy the key: the handshake wipes its own derived-key
    // arrays in a finally right after constructing the ciphers (§2.8). Without a copy, that wipe
    // would zero the cipher's live key and the very first packet would be encrypted under zeros.
    @Test
    fun constructor_copiesKey_survivesSourceArrayWipeAfterConstruction() {
        val v = FSCHACHA20_VECTORS.first()
        val plaintext = v.plainHex.hexToBytes()
        val keySource = v.keyHex.hexToBytes()

        val cipher = FSChaCha20(keySource, v.rekeyInterval)
        keySource.fill(0) // simulate the handshake's finally-block wipe of its own key array

        repeat(v.rekeyInterval) { cipher.crypt(plaintext) }
        val actual = cipher.crypt(plaintext)

        assertEquals(v.cipherAfterRotationHex, actual.toHex())
    }

    @Test
    fun constructor_keyNot32Bytes_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) { FSChaCha20(ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { FSChaCha20(ByteArray(33)) }
    }

    @Test
    fun wipe_zerosLiveKeyAndIsIdempotent() {
        val cipher = FSChaCha20(ByteArray(32) { it.toByte() })

        cipher.wipe()

        assertTrue(cipher.isKeyWiped())
        cipher.wipe() // must not throw when called again on an already-wiped instance
        assertTrue(cipher.isKeyWiped())
    }
}

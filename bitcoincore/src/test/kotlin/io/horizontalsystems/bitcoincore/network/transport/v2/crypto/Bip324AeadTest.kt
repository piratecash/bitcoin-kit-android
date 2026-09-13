package io.horizontalsystems.bitcoincore.network.transport.v2.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Bip324AeadTest {

    private data class AeadVector(val plainHex: String, val aadHex: String, val keyHex: String, val nonceHex: String, val cipherHex: String)

    // RFC8439 AEAD_CHACHA20_POLY1305 vectors, ported from Bitcoin Core's
    // test_framework/crypto/bip324_cipher.py: AEAD_TESTS (RFC 8439 §2.8.2 and §A.5, plus two
    // vectors exercising aad/plaintext lengths that are exact multiples of 16 bytes).
    private val AEAD_VECTORS = listOf(
        AeadVector("4c616469657320616e642047656e746c656d656e206f662074686520636c617373206f66202739393a204966204920636f756c64206f6666657220796f75206f6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73637265656e20776f756c642062652069742e", "50515253c0c1c2c3c4c5c6c7", "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f", "070000004041424344454647", "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691"),
        AeadVector("496e7465726e65742d4472616674732061726520647261667420646f63756d656e74732076616c696420666f722061206d6178696d756d206f6620736978206d6f6e74687320616e64206d617920626520757064617465642c207265706c616365642c206f72206f62736f6c65746564206279206f7468657220646f63756d656e747320617420616e792074696d652e20497420697320696e617070726f70726961746520746f2075736520496e7465726e65742d447261667473206173207265666572656e6365206d6174657269616c206f7220746f2063697465207468656d206f74686572207468616e206173202fe2809c776f726b20696e2070726f67726573732e2fe2809d", "f33388860000000000004e91", "1c9240a5eb55d38af333888604f6b5f0473917c1402b80099dca5cbc207075c0", "000000000102030405060708", "64a0861575861af460f062c79be643bd5e805cfd345cf389f108670ac76c8cb24c6cfc18755d43eea09ee94e382d26b0bdb7b73c321b0100d4f03b7f355894cf332f830e710b97ce98c8a84abd0b948114ad176e008d33bd60f982b1ff37c8559797a06ef4f0ef61c186324e2b3506383606907b6a7c02b0f9f6157b53c867e4b9166c767b804d46a59b5216cde7a4e99040c5a40433225ee282a1b0a06c523eaf4534d7f83fa1155b0047718cbc546a0d072b04b3564eea1b422273f548271a0bb2316053fa76991955ebd63159434ecebb4e466dae5a1073a6727627097a1049e617d91d361094fa68f0ff77987130305beaba2eda04df997b714d6c6f2c29a6ad5cb4022b02709beead9d67890cbb22392336fea1851f38"),
        AeadVector("8d2d6a8befd9716fab35819eaac83b33269afb9f1a00fddf66095a6c0cd91951a6b7ad3db580be0674c3f0b55f618e34", "", "72ddc73f07101282bbbcf853b9012a9f9695fc5d36b303a97fd0845d0314e0c3", "5fb7323424407feb375558b3", "f760b8224fb2a317b1b07875092606131232a5b86ae142df5df1c846a7f6341af2564483dd77f836be45e6230808ffe402a6f0a3e8be074b3d1f4ea8a7b09451"),
        AeadVector("", "36970d8a704c065de16250c18033de5a400520ac1b5842b24551e5823a3314f3946285171e04a81ebfbe3566e312e74ab80e94c7dd2ff4e10de0098a58d0f503", "77adda51d6730b9ad6c995658cbd49f581b2547e7c0c08fcc24ceec797461021", "88da901fa47144f83efada75", "aaae5bb81e8407c94b2ae86ae0c7efbe"),
    )

    @Test
    fun encrypt_rfc8439Vectors_matchesReference() {
        for (v in AEAD_VECTORS) {
            val actual = Bip324Aead.encrypt(v.keyHex.hexToBytes(), v.nonceHex.hexToBytes(), v.aadHex.hexToBytes(), v.plainHex.hexToBytes())
            assertEquals(v.cipherHex, actual.toHex())
        }
    }

    @Test
    fun decrypt_rfc8439Vectors_recoversPlaintext() {
        for (v in AEAD_VECTORS) {
            val actual = Bip324Aead.decrypt(v.keyHex.hexToBytes(), v.nonceHex.hexToBytes(), v.aadHex.hexToBytes(), v.cipherHex.hexToBytes())
            assertEquals(v.plainHex, actual?.toHex())
        }
    }

    @Test
    fun decrypt_tamperedTag_returnsNull() {
        val v = AEAD_VECTORS.first()
        val tampered = v.cipherHex.hexToBytes()
        tampered[tampered.size - 1] = (tampered.last().toInt() xor 1).toByte()

        val result = Bip324Aead.decrypt(v.keyHex.hexToBytes(), v.nonceHex.hexToBytes(), v.aadHex.hexToBytes(), tampered)

        assertNull(result)
    }

    @Test
    fun decrypt_tooShortForTag_returnsNull() {
        val v = AEAD_VECTORS.first()

        val result = Bip324Aead.decrypt(v.keyHex.hexToBytes(), v.nonceHex.hexToBytes(), v.aadHex.hexToBytes(), ByteArray(4))

        assertNull(result)
    }

    private data class FsAeadVector(val plainHex: String, val aadHex: String, val keyHex: String, val msgIdx: Int, val cipherHex: String)

    // FSChaCha20Poly1305 vectors, ported from Bitcoin Core's test_framework/crypto/bip324_cipher.py:
    // FSAEAD_TESTS. Each vector skips msgIdx packets (crossing rekey boundaries along the way,
    // since the default rekey interval is 224) before the packet actually being checked.
    private val FS_AEAD_VECTORS = listOf(
        FsAeadVector("d6a4cb04ef0f7c09c1866ed29dc24d820e75b0491032a51b4c3366f9ca35c19ea3047ec6be9d45f9637b63e1cf9eb4c2523a5aab7b851ebeba87199db0e839cf0d5c25e50168306377aedbe9089fd2463ded88b83211cf51b73b150608cc7a600d0f11b9a742948482e1b109d8faf15b450aa7322e892fa2208c6691e3fecf4c711191b14d75a72147", "786cb9b6ebf44288974cf0", "5c9e1c3951a74fba66708bf9d2c217571684556b6a6a3573bff2847d38612654", 500, "9dcebbd3281ea3dd8e9a1ef7d55a97abd6743e56ebc0c190cb2c4e14160b385e0bf508dddf754bd02c7c208447c131ce23e47a4a14dfaf5dd8bc601323950f754e05d46e9232f83fc5120fbbef6f5347a826ec79a93820718d4ec7a2b7cfaaa44b21e16d726448b62f803811aff4f6d827ed78e738ce8a507b81a8ae131311928039213de18a5120dc9b7370baca878f50ff254418de3da50c"),
        FsAeadVector("8349b7a2690b63d01204800c288ff1138a1d473c832c90ea8b3fc102d0bb3adc44261b247c7c3d6760bfbe979d061c305f46d94c0582ac3099f0bf249f8cb234", "", "3bd2093fcbcb0d034d8c569583c5425c1a53171ea299f8cc3bbf9ae3530adfce", 60000, "30a6757ff8439b975363f166a0fa0e36722ab35936abd704297948f45083f4d499433137ce931f7fca28a0acd3bc30f57b550acbc21cbd45bbef0739d9caf30c14b94829deb27f0b1923a2af704ae5d6"),
    )

    // Bitcoin Core's Python reference calls encrypt(aad, plaintext=None) to advance state without
    // performing real encryption. Our API has no null-plaintext overload, but the skip is
    // equivalent to a real 0-byte encrypt/decrypt call: the rekey step depends only on the packet
    // counter and current key, never on the (discarded) output of the skipped packet.
    @Test
    fun fsChaCha20Poly1305_encryptAfterSkippingPackets_matchesReference() {
        for (v in FS_AEAD_VECTORS) {
            val cipher = FSChaCha20Poly1305(v.keyHex.hexToBytes())
            repeat(v.msgIdx) { cipher.encrypt(ByteArray(0), ByteArray(0)) }
            val actual = cipher.encrypt(v.aadHex.hexToBytes(), v.plainHex.hexToBytes())
            assertEquals(v.cipherHex, actual.toHex())
        }
    }

    @Test
    fun fsChaCha20Poly1305_decryptAfterSkippingPackets_matchesReference() {
        for (v in FS_AEAD_VECTORS) {
            val cipher = FSChaCha20Poly1305(v.keyHex.hexToBytes())
            repeat(v.msgIdx) { cipher.decrypt(ByteArray(0), ByteArray(0)) }
            val actual = cipher.decrypt(v.aadHex.hexToBytes(), v.cipherHex.hexToBytes())
            assertEquals(v.plainHex, actual?.toHex())
        }
    }

    // The constructor must defensively copy the key: the handshake wipes its own derived-key
    // arrays in a finally right after constructing the ciphers (§2.8). Without a copy, that wipe
    // would zero the cipher's live key and the very first packet would be encrypted under zeros.
    @Test
    fun constructor_copiesKey_survivesSourceArrayWipeAfterConstruction() {
        val v = FS_AEAD_VECTORS.first()
        val keySource = v.keyHex.hexToBytes()

        val cipher = FSChaCha20Poly1305(keySource)
        keySource.fill(0) // simulate the handshake's finally-block wipe of its own key array

        repeat(v.msgIdx) { cipher.encrypt(ByteArray(0), ByteArray(0)) }
        val actual = cipher.encrypt(v.aadHex.hexToBytes(), v.plainHex.hexToBytes())

        assertEquals(v.cipherHex, actual.toHex())
    }

    @Test
    fun constructor_keyNot32Bytes_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) { FSChaCha20Poly1305(ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { FSChaCha20Poly1305(ByteArray(33)) }
    }

    @Test
    fun wipe_zerosLiveKeyAndIsIdempotent() {
        val cipher = FSChaCha20Poly1305(ByteArray(32) { it.toByte() })

        cipher.wipe()

        assertTrue(cipher.isKeyWiped())
        cipher.wipe() // must not throw when called again on an already-wiped instance
        assertTrue(cipher.isKeyWiped())
    }
}

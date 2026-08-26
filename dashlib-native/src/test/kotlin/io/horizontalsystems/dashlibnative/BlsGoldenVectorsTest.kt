package io.horizontalsystems.dashlibnative

import io.horizontalsystems.dashlib.BLS
import org.dashj.bls.InsecureSignature
import org.dashj.bls.PrivateKey
import org.dashj.bls.PublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Consensus-equivalence gate: every packaged native must reproduce the upstream Chia
 * vectors bit for bit. Runs on each matrix platform, so the four binaries are proven
 * to agree rather than merely to link.
 */
class BlsGoldenVectorsTest {

    private val vectors: Map<String, String> = readVectors()

    private val seed = hex("seed")
    private val sk = hex("sk")
    private val pk = hex("pk")
    private val msg = hex("msg")
    private val msgHash = hex("msgHash")
    private val sig = hex("sig")
    private val pkFingerprint = vectors.getValue("pkFingerprint").toLong(16)

    @Test
    fun bindingSizes_matchUpstreamLiterals() {
        assertTrue(DashjBlsLibrary.available)

        assertEquals(32L, PrivateKey.PRIVATE_KEY_SIZE)
        assertEquals(48L, PublicKey.PUBLIC_KEY_SIZE)
        assertEquals(96L, InsecureSignature.SIGNATURE_SIZE)
    }

    @Test
    fun messageHash_isSingleSha256() {
        assertArrayEquals(msgHash, MessageDigest.getInstance("SHA-256").digest(msg))
    }

    @Test
    fun privateKeyFromSeed_serializesToGoldenVector() {
        assertTrue(DashjBlsLibrary.available)

        val buffer = ByteArray(PrivateKey.PRIVATE_KEY_SIZE.toInt())
        PrivateKey.FromSeed(seed, seed.size.toLong()).Serialize(buffer)

        assertArrayEquals(sk, buffer)
    }

    @Test
    fun publicKey_serializesToGoldenVectorAndFingerprint() {
        assertTrue(DashjBlsLibrary.available)

        val publicKey = PrivateKey.FromSeed(seed, seed.size.toLong()).GetPublicKey()
        val buffer = ByteArray(PublicKey.PUBLIC_KEY_SIZE.toInt())
        publicKey.Serialize(buffer)

        assertArrayEquals(pk, buffer)
        assertEquals(pkFingerprint, publicKey.GetFingerprint())
    }

    @Test
    fun signInsecurePrehashed_serializesToGoldenVector() {
        assertTrue(DashjBlsLibrary.available)

        val buffer = ByteArray(InsecureSignature.SIGNATURE_SIZE.toInt())
        PrivateKey.FromSeed(seed, seed.size.toLong()).SignInsecurePrehashed(msgHash).Serialize(buffer)

        assertArrayEquals(sig, buffer)
    }

    @Test
    fun verifySignature_goldenVector_returnsTrue() {
        assertTrue(DashjBlsLibrary.available)

        assertTrue(BLS().verifySignature(pk, sig, msgHash))
    }

    @Test
    fun verifySignature_tamperedHash_returnsFalse() {
        assertTrue(DashjBlsLibrary.available)

        val tampered = msgHash.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0xFF).toByte()

        assertFalse(BLS().verifySignature(pk, sig, tampered))
    }

    private fun hex(key: String): ByteArray {
        val value = vectors.getValue(key)
        require(value.length % 2 == 0) { "golden vector '$key' is not whole bytes" }
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun readVectors(): Map<String, String> {
        val stream = checkNotNull(javaClass.getResourceAsStream(RESOURCE)) {
            "$RESOURCE missing from the test classpath: the consensus gate would silently pass"
        }
        val parsed = stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    val separator = line.indexOf('=')
                    require(separator > 0) { "unparseable golden vector line: '$line'" }
                    line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()
        }
        val missing = REQUIRED_KEYS - parsed.keys
        require(missing.isEmpty()) { "$RESOURCE is missing golden vectors: $missing" }
        return parsed
    }

    private companion object {
        const val RESOURCE = "/bls-golden-vectors.txt"
        val REQUIRED_KEYS = setOf("seed", "sk", "pkFingerprint", "pk", "msg", "msgHash", "sig")
    }
}

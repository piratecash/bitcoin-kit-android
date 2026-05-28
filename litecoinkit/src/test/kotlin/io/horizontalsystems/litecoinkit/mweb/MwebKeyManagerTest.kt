package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.bitcoincore.extensions.toHexString
import org.junit.Assert.assertEquals
import org.junit.Test

class MwebKeyManagerTest {
    @Test
    fun accountKeys_pinnedSeeds_matchMwebdRecommendedPaths() {
        val vectors = listOf(
            KeyVector(
                seed = ByteArray(64),
                scanSecret = "8111873a68eea9226633679fdbb7c722febb2faf9cb466f774abb5687bb507bf",
                spendSecret = "eb38f3bc766af919cbb291647d1c2353acfd062b0190b69c8578a0e8f58f72a2",
                spendPublicKey = "027ea462511404c1eb774f0e9c4fda2d2930e40a69d27e7b48c11201e2830d0d0c",
            ),
            KeyVector(
                seed = ByteArray(64) { 1 },
                scanSecret = "8dd35409f81dd47805a76391111ac1d764b6ea9569e3587b4cdfbb4f3b155af4",
                spendSecret = "ca179da5f5faf782c03ce8191c909b27130a6e6b1feac596b578526ad6a26cb9",
                spendPublicKey = "0395f127a0d513b9035e8931251fef6c70935e257f518f3f966da245cf9de05274",
            ),
            KeyVector(
                seed = ByteArray(64) { it.toByte() },
                scanSecret = "d891b2aac7e5979367cec21384dc94b4168ad25289971a92f091f0cc83adef2a",
                spendSecret = "4863df2032edd75ab30cac83f8191ac9e10cde6545250479435d71a050a2ab9f",
                spendPublicKey = "0324f15c07295a3668679c20c3045c26cc2bc745bce55cc0abc52c6e63930a78c2",
            ),
        )

        vectors.forEach { vector ->
            val keys = MwebKeyManager(vector.seed).accountKeys()

            assertEquals(vector.scanSecret, keys.scanSecret.toHexString())
            assertEquals(vector.spendSecret, keys.spendSecret.toHexString())
            assertEquals(vector.spendPublicKey, keys.spendPublicKey.toHexString())
        }
    }

    private class KeyVector(
        val seed: ByteArray,
        val scanSecret: String,
        val spendSecret: String,
        val spendPublicKey: String,
    )
}

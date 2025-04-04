package io.horizontalsystems.cosantakit

import com.github.all3fox.lyra2.Lyra2
import com.github.all3fox.lyra2.LyraParams
import fr.cryptohash.*
import io.horizontalsystems.bitcoincore.core.IHasher
import org.bouncycastle.crypto.digests.GOST3411_2012_512Digest

internal class X11HasherExt : IHasher {
    private val algorithms = listOf(
        BLAKE512(),
        BMW512(),
        Groestl512(),
        Skein512(),
        JH512(),
        Keccak512(),
        Luffa512(),
        CubeHash512(),
        SHAvite512(),
        SIMD512(),
        ECHO512(),
        Hamsi512(),
        Fugue512(),
        Shabal512(),
        Whirlpool(),
        SHA512(),
        HAVAL256_5()
    )

    private fun gost512(data: ByteArray): ByteArray {
        val digest = GOST3411_2012_512Digest()
        digest.update(data, 0, data.size)

        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)

        return out
    }

    private fun runLyra2(input: ByteArray): ByteArray {
        val output = ByteArray(32)

        val params = LyraParams(
            /* klen = */ 32,
            /* t_cost = */ 2,
            /* m_cost = */ 66,
            /* N_COLS = */ 66,
            /* SPONGE = */ "blake2b",
            /* FULL_ROUNDS = */ 12,
            /* HALF_ROUNDS = */ 12,
            /* BLOCK_LEN_INT64 = */ 8
            // из реализации
        )
        // В оригинале и password и salt = hash17
        Lyra2.phs(output, input, input, params)

        return output
    }

    override fun hash(data: ByteArray): ByteArray {
        var hash = data
        println("X11HasherExt, size: ${data.size}")

        algorithms.forEach {
            println("Apply: ${it.javaClass}")
            hash = it.digest(hash)
        }
        hash = gost512(hash)
        return runLyra2(hash.copyOf(80))
    }
}

package io.horizontalsystems.dashkit.instantsend

import org.dashj.bls.InsecureSignature
import org.dashj.bls.JNI
import org.dashj.bls.PublicKey
import timber.log.Timber

class BLS {
    private val logTag = "BLS"

    init {
        System.loadLibrary(JNI.LIBRARY_NAME)
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun verifySignature(
        pubKeyOperator: ByteArray,
        vchMasternodeSignature: ByteArray,
        hash: ByteArray
    ): Boolean {
        return try {
            val pk = PublicKey.FromBytes(pubKeyOperator)
            val insecureSignature = InsecureSignature.FromBytes(vchMasternodeSignature)

            insecureSignature.Verify(hash, pk)
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Verifying BLS signature failed")

            false
        }
    }
}

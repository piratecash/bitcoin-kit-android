package io.horizontalsystems.dashlib

import org.dashj.bls.InsecureSignature
import org.dashj.bls.JNI
import org.dashj.bls.PublicKey
import java.util.logging.Level
import java.util.logging.Logger

class BLS {
    private val logger = Logger.getLogger("BLS")

    init {
        try {
            System.loadLibrary(JNI.LIBRARY_NAME)
        } catch (e: LinkageError) {
            notPreloaded(e)
        } catch (e: Exception) {
            notPreloaded(e)
        }
    }

    fun verifySignature(
        pubKeyOperator: ByteArray,
        vchMasternodeSignature: ByteArray,
        hash: ByteArray
    ): Boolean {
        return try {
            val pk = PublicKey.FromBytes(pubKeyOperator)
            val insecureSignature = InsecureSignature.FromBytes(vchMasternodeSignature)

            insecureSignature.Verify(hash, pk)
        } catch (e: LinkageError) {
            verificationFailed(e)
        } catch (e: Exception) {
            verificationFailed(e)
        }
    }

    // Desktop hosts preload the native from dashlib-native; only Android loads it here.
    private fun notPreloaded(e: Throwable) =
        logger.log(Level.INFO, "dashjbls not in java.library.path, expecting host preload", e)

    private fun verificationFailed(e: Throwable): Boolean {
        logger.log(Level.SEVERE, "Verifying BLS signature failed", e)
        return false
    }
}

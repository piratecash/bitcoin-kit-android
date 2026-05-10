package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.litecoinkit.LitecoinKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.min
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface MwebCanonicalTransactionHashProvider {
    suspend fun transactionHash(height: Int): String?
}

internal object EmptyMwebCanonicalTransactionHashProvider : MwebCanonicalTransactionHashProvider {
    override suspend fun transactionHash(height: Int): String? = null
}

internal class MwebExplorerCanonicalTransactionHashProvider(
    private val blockPageProvider: suspend (Int) -> String? = ::fetchBlockPage,
    private val currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() },
) : MwebCanonicalTransactionHashProvider {
    private val cache = ConcurrentHashMap<Int, CacheEntry>()

    override suspend fun transactionHash(height: Int): String? {
        if (height <= 0) return null
        val now = currentTimeMillisProvider()
        cache[height]?.takeIf { !it.isExpired(now) }?.let { return it.hash }

        val hash = try {
            blockPageProvider(height)?.let { parseTransactionHash(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.INFO, "Failed to fetch MWEB block $height", error)
            null
        }

        cache[height] = CacheEntry(
            hash = hash,
            expiresAt = if (hash == null) now + NEGATIVE_CACHE_TTL_MILLIS else Long.MAX_VALUE,
        )
        return hash
    }

    companion object {
        fun create(networkType: LitecoinKit.NetworkType): MwebCanonicalTransactionHashProvider {
            return when (networkType) {
                LitecoinKit.NetworkType.MainNet -> MwebExplorerCanonicalTransactionHashProvider()
                LitecoinKit.NetworkType.TestNet -> EmptyMwebCanonicalTransactionHashProvider
            }
        }

        fun parseTransactionHash(html: String): String? {
            val labelIndex = html.indexOf(TRANSACTION_HASH_LABEL, ignoreCase = true)
            if (labelIndex < 0) return null

            val searchArea = html.substring(labelIndex, min(html.length, labelIndex + HASH_SEARCH_WINDOW))
            return HASH_REGEX.find(searchArea)?.value?.lowercase()
        }

        private suspend fun fetchBlockPage(height: Int): String? = suspendCancellableCoroutine { continuation ->
            val connection = URL("$MWEB_EXPLORER_URL/blocks/block/$height").openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = CONNECTION_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            continuation.invokeOnCancellation { connection.disconnect() }

            try {
                val html = connection.inputStream.bufferedReader().use { it.readText() }
                if (continuation.isActive) {
                    continuation.resume(html)
                }
            } catch (error: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            } finally {
                connection.disconnect()
            }
        }

        private const val MWEB_EXPLORER_URL = "https://www.mwebexplorer.com"
        private const val USER_AGENT = "bitcoin-kit-android"
        private const val TRANSACTION_HASH_LABEL = "MWEB Transaction Hash:"
        private const val HASH_SEARCH_WINDOW = 1_000
        private const val CONNECTION_TIMEOUT_MILLIS = 5_000
        private const val READ_TIMEOUT_MILLIS = 10_000
        private const val NEGATIVE_CACHE_TTL_MILLIS = 5 * 60 * 1_000L
        private val HASH_REGEX = Regex("[0-9a-fA-F]{64}")
        private val logger = Logger.getLogger(MwebExplorerCanonicalTransactionHashProvider::class.java.name)
    }

    private data class CacheEntry(
        val hash: String?,
        val expiresAt: Long,
    ) {
        fun isExpired(now: Long): Boolean {
            return now >= expiresAt
        }
    }
}

package io.horizontalsystems.bitcoincore.managers

import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonValue
import io.horizontalsystems.bitcoincore.network.NetworkErrorListenerHolder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class ApiManager(
    private val host: String,
    private val networkErrorListener: NetworkErrorListenerHolder? = null
) {
    private val logger = Logger.getLogger("ApiManager")

    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1000L

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(5000, TimeUnit.MILLISECONDS)
            .readTimeout(60000, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun <T> retryOnServerError(maxRetries: Int = MAX_RETRIES, operation: (attempt: Int) -> T): T {
        var lastException: Exception? = null

        for (attempt in 0 until maxRetries) {
            try {
                return operation(attempt)
            } catch (e: ApiManagerException.Http500Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val backoffTime = INITIAL_BACKOFF_MS * (1 shl attempt) // Exponential: 1s, 2s, 4s
                    logger.warning("Retry attempt ${attempt + 1}/$maxRetries after ${backoffTime}ms due to: ${e.message}")
                    Thread.sleep(backoffTime)
                }
            }
        }

        throw lastException ?: ApiManagerException.Other("Retry failed without exception")
    }

    @Throws
    fun get(resource: String): InputStream? {
        val url = "$host/$resource"

        logger.info("Fetching $url")

        return try {
            URL(url)
                .openConnection()
                .apply {
                    connectTimeout = 5000
                    readTimeout = 60000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("content-type", "application/json")
                }.getInputStream()
        } catch (exception: IOException) {
            networkErrorListener?.emit(source = host, method = "GET", url = url, throwable = exception)
            throw ApiManagerException.Other("${exception.javaClass.simpleName}: $host")
        }
    }

    @Throws
    fun post(resource: String, data: String): JsonValue {
        try {
            val path = "$host/$resource"

            logger.info("Fetching $path")

            val url = URL(path)
            val urlConnection = url.openConnection() as HttpURLConnection
            urlConnection.requestMethod = "POST"
            urlConnection.setRequestProperty("Content-Type", "application/json")
            val out = BufferedOutputStream(urlConnection.outputStream)
            val writer = BufferedWriter(OutputStreamWriter(out, "UTF-8"))
            writer.write(data)
            writer.flush()
            writer.close()
            out.close()

            return urlConnection.inputStream.use {
                Json.parse(it.bufferedReader())
            }
        } catch (exception: IOException) {
            networkErrorListener?.emit(source = host, method = "POST", url = "$host/$resource", throwable = exception)
            throw ApiManagerException.Other("${exception.javaClass.simpleName}: $host")
        }
    }

    fun doOkHttpGetAsString(uri: String): String? {
        return executeGet(uri) { response ->
            response.body?.string()
        }
    }

    fun doOkHttpGet(uri: String): JsonValue {
        return executeGet(uri) { response ->
            response.body?.let {
                Json.parse(it.string())
            } ?: throw ApiManagerException.Other("Empty response body: $host")
        }
    }

    private fun <T> executeGet(uri: String, parse: (Response) -> T): T {
        return retryOnServerError {
            val url = "$host/$uri"

            try {
                httpClient.newCall(Request.Builder().url(url).build())
                    .execute()
                    .use { response ->
                        when {
                            response.isSuccessful -> parse(response)
                            response.code == 404 -> throw ApiManagerException.Http404Exception
                            response.code in 500..599 -> {
                                logger.warning("Server error ${response.code} for URL: $url - ${response.message}")
                                throw ApiManagerException.Http500Exception(url, response.code)
                            }
                            else -> {
                                logger.warning("Unexpected error ${response.code} for URL: $url - ${response.message}")
                                throw ApiManagerException.Other("Unexpected Error:$response")
                            }
                        }
                    }
            } catch (e: ApiManagerException) {
                throw e
            } catch (e: Exception) {
                if (e is IOException) {
                    networkErrorListener?.emit(source = host, method = "GET", url = url, throwable = e)
                }
                throw ApiManagerException.Other("${e.javaClass.simpleName}: $host, ${e.localizedMessage}")
            }
        }
    }
}

sealed class ApiManagerException : Exception() {
    object Http404Exception : ApiManagerException()
    data class Http500Exception(val url: String, val responseCode: Int) : ApiManagerException() {
        override val message: String
            get() = "Server error $responseCode for URL: $url"
    }
    class Other(override val message: String) : ApiManagerException()
}

package io.horizontalsystems.bitcoincore.managers

import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonValue
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class ApiManager(private val host: String) {
    private val logger = Logger.getLogger("ApiManager")

    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1000L
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
            throw ApiManagerException.Other("${exception.javaClass.simpleName}: $host")
        }
    }

    fun doOkHttpGetAsString(uri: String): String? {
        return retryOnServerError { attempt ->
            val url = "$host/$uri"

            try {
                val httpClient: OkHttpClient = OkHttpClient.Builder()
                    .apply {
                        connectTimeout(5000, TimeUnit.MILLISECONDS)
                        readTimeout(60000, TimeUnit.MILLISECONDS)
                    }.build()

                httpClient.newCall(Request.Builder().url(url).build())
                    .execute()
                    .use { response ->

                        if (response.isSuccessful) {
                            return@retryOnServerError response.body?.string()
                        }

                        when (response.code) {
                            404 -> throw ApiManagerException.Http404Exception
                            in 500..599 -> {
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
                throw ApiManagerException.Other("${e.javaClass.simpleName}: $host, ${e.localizedMessage}")
            }
        }
    }

    fun doOkHttpGet(uri: String): JsonValue {
        return retryOnServerError { attempt ->
            val url = "$host/$uri"

            try {
                val httpClient: OkHttpClient = OkHttpClient.Builder()
                    .apply {
                        connectTimeout(5000, TimeUnit.MILLISECONDS)
                        readTimeout(60000, TimeUnit.MILLISECONDS)
                    }.build()

                httpClient.newCall(Request.Builder().url(url).build())
                    .execute()
                    .use { response ->

                        if (response.isSuccessful) {
                            response.body?.let {
                                return@retryOnServerError Json.parse(it.string())
                            }
                        }

                        when (response.code) {
                            404 -> throw ApiManagerException.Http404Exception
                            in 500..599 -> {
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

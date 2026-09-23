package io.horizontalsystems.bitcoincore.managers

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

class ApiManagerTest {

    @Test
    fun doOkHttpGetAsString_separateManagers_reusesConnection() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("first"))
        server.enqueue(MockResponse().setBody("second"))
        server.start()

        try {
            val host = server.url("/").toString().removeSuffix("/")

            assertEquals("first", ApiManager(host).doOkHttpGetAsString("first"))
            assertEquals("second", ApiManager(host).doOkHttpGetAsString("second"))
            assertEquals(0, server.takeRequest().sequenceNumber)
            assertEquals(1, server.takeRequest().sequenceNumber)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun doOkHttpGet_streamFailsThenSucceeds_retriesAndReturnsBody() {
        withServer(
            MockResponse().apply { socketPolicy = SocketPolicy.DISCONNECT_AT_START },
            MockResponse().setBody("recovered")
        ) { server, host ->
            assertEquals("recovered", ApiManager(host).doOkHttpGetAsString("resource"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun doOkHttpGet_unexpectedStatus_isNotRetried() {
        withServer(MockResponse().setResponseCode(400)) { server, host ->
            val error = assertThrows { ApiManager(host).doOkHttpGetAsString("resource") }

            assertTrue(error is ApiManagerException.Other)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun doOkHttpGet_networkFailureThenServerErrors_throwsNetworkException() {
        withServer(
            MockResponse().apply { socketPolicy = SocketPolicy.DISCONNECT_AT_START },
            MockResponse().setResponseCode(503),
            MockResponse().setResponseCode(503)
        ) { server, host ->
            val error = assertThrows { ApiManager(host).doOkHttpGetAsString("resource") }

            assertTrue(error is ApiManagerException.NetworkException)
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun doOkHttpGet_onlyServerErrors_throwsHttp500() {
        withServer(
            MockResponse().setResponseCode(503),
            MockResponse().setResponseCode(503),
            MockResponse().setResponseCode(503)
        ) { server, host ->
            val error = assertThrows { ApiManager(host).doOkHttpGetAsString("resource") }

            assertTrue(error is ApiManagerException.Http500Exception)
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun doOkHttpGet_networkFailure_messageKeepsTheHostAndDropsTheRequestUri() {
        val disconnect = { MockResponse().apply { socketPolicy = SocketPolicy.DISCONNECT_AT_START } }

        withServer(disconnect(), disconnect(), disconnect()) { _, host ->
            val error = assertThrows { ApiManager(host).doOkHttpGetAsString("history?api_key=SECRET") }

            assertTrue(error.message.orEmpty().contains(host.substringAfter("://")))
            assertFalse(error.message.orEmpty().contains("SECRET"))
        }
    }

    @Test
    fun doOkHttpGet_networkFailure_messageDropsCredentialsFromTheBaseUrl() {
        val disconnect = { MockResponse().apply { socketPolicy = SocketPolicy.DISCONNECT_AT_START } }

        withServer(disconnect(), disconnect(), disconnect()) { _, host ->
            val authority = host.substringAfter("://")
            val credentialed = ApiManager("http://user:SECRET@$authority/v1/TOKEN")

            val error = assertThrows { credentialed.doOkHttpGetAsString("history") }

            assertTrue(error.message.orEmpty().contains(authority))
            assertFalse(error.message.orEmpty().contains("SECRET"))
            assertFalse(error.message.orEmpty().contains("TOKEN"))
        }
    }

    @Test
    fun doOkHttpGet_networkFailure_messageDropsAQueryAttachedToTheBaseUrl() {
        val disconnect = { MockResponse().apply { socketPolicy = SocketPolicy.DISCONNECT_AT_START } }

        withServer(disconnect(), disconnect(), disconnect()) { _, host ->
            val queried = ApiManager("$host?api_key=SECRET")

            val error = assertThrows { queried.doOkHttpGetAsString("history") }

            assertTrue(error is ApiManagerException.NetworkException)
            assertFalse(error.message.orEmpty().contains("SECRET"))
        }
    }

    @Test
    fun doOkHttpGet_networkFailure_retryLogOmitsTheCauseMessage() {
        // A malformed status line makes OkHttp raise a ProtocolException quoting the request target.
        val malformed = { MockResponse().setStatus("GET /history?api_key=SECRET HTTP/1.1") }
        val records = captureApiManagerLog {
            withServer(malformed(), malformed(), malformed()) { _, host ->
                assertThrows { ApiManager(host).doOkHttpGetAsString("history?api_key=SECRET") }
            }
        }

        assertTrue(records.isNotEmpty())
        assertTrue(records.none { it.message.orEmpty().contains("SECRET") })
    }

    @Test
    fun doOkHttpGet_serverErrors_logOmitsTheRequestUri() {
        val serverError = { MockResponse().setResponseCode(503) }
        val records = captureApiManagerLog {
            withServer(serverError(), serverError(), serverError()) { _, host ->
                val error = assertThrows { ApiManager(host).doOkHttpGetAsString("history?api_key=SECRET") }

                assertFalse(error.message.orEmpty().contains("SECRET"))
            }
        }

        assertTrue(records.isNotEmpty())
        assertTrue(records.none { it.message.orEmpty().contains("SECRET") })
    }

    @Test
    fun doOkHttpGet_unexpectedStatus_messageAndLogOmitTheRequestUri() {
        val records = captureApiManagerLog {
            withServer(MockResponse().setResponseCode(400)) { _, host ->
                val error = assertThrows { ApiManager(host).doOkHttpGetAsString("history?api_key=SECRET") }

                assertFalse(error.message.orEmpty().contains("SECRET"))
            }
        }

        assertTrue(records.isNotEmpty())
        assertTrue(records.none { it.message.orEmpty().contains("SECRET") })
    }

    private fun captureApiManagerLog(block: () -> Unit): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records.add(record)
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        val logger = Logger.getLogger("ApiManager")
        logger.addHandler(handler)

        try {
            block()
        } finally {
            logger.removeHandler(handler)
        }
        return records
    }

    private fun withServer(vararg responses: MockResponse, test: (MockWebServer, String) -> Unit) {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()

        try {
            test(server, server.url("/").toString().removeSuffix("/"))
        } finally {
            server.shutdown()
        }
    }

    private fun assertThrows(block: () -> Unit): Throwable =
        try {
            block()
            throw AssertionError("Expected an exception, none was thrown")
        } catch (error: AssertionError) {
            throw error
        } catch (error: Throwable) {
            error
        }
}

package io.horizontalsystems.bitcoincore.managers

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

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
}

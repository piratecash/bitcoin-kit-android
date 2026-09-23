package io.horizontalsystems.bitcoincore.apisync.blockchair

import io.horizontalsystems.bitcoincore.managers.ApiManager
import io.horizontalsystems.bitcoincore.managers.ApiManagerException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockchairApiServerErrorTest {

    @Test
    fun transactions_serverErrorOutlivesTheRetries_failsInsteadOfReturningEmpty() {
        withFailingServer { api ->
            assertThrown { api.transactions(listOf("ltc1qexample"), 0) }
        }
    }

    @Test
    fun blockHashes_serverErrorOutlivesTheRetries_failsInsteadOfReturningEmpty() {
        withFailingServer { api ->
            assertThrown { api.blockHashes(listOf(3_100_000)) }
        }
    }

    // A swallowed server error would let the scan finish, mark the wallet restored and report
    // success with none of its history.
    private fun assertThrown(block: () -> Unit) {
        val error = try {
            block()
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(error is ApiManagerException.Http500Exception)
    }

    private fun withFailingServer(test: (BlockchairApi) -> Unit) {
        val server = MockWebServer()
        repeat(SERVER_ERRORS) { server.enqueue(MockResponse().setResponseCode(503)) }
        server.start()

        try {
            val host = server.url("/").toString().removeSuffix("/")
            test(BlockchairApi("litecoin", null, ApiManager(host)))
        } finally {
            server.shutdown()
        }
    }

    private companion object {
        // One per attempt of ApiManager's request-level retry budget.
        const val SERVER_ERRORS = 3
    }
}

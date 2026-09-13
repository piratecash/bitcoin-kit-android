package io.horizontalsystems.bitcoincore.apisync.blockchair

import io.horizontalsystems.bitcoincore.managers.ApiManager
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockchairApiRequestLengthTest {

    @Test
    fun transactions_taprootAddresses_everyRequestTargetStaysWithinBudget() {
        val targets = capture(addresses(count = 100, length = TAPROOT_LENGTH, prefix = "bc1p"))

        assertTrue(targets.isNotEmpty())
        targets.forEach {
            assertTrue("request target was ${it.length} B: $it", it.length <= MAX_REQUEST_TARGET)
        }
    }

    @Test
    fun transactions_taprootAddresses_everyAddressIsRequestedExactlyOnce() {
        val addresses = addresses(count = 100, length = TAPROOT_LENGTH, prefix = "bc1p")

        assertEquals(addresses, capture(addresses).flatMap { it.requestedAddresses() })
    }

    @Test
    fun transactions_moreThanTheCountCeiling_splitsAtOneHundred() {
        val addresses = addresses(count = 101, length = LEGACY_LENGTH, prefix = "1")

        val batches = capture(addresses).map { it.requestedAddresses() }

        assertEquals(listOf(100, 1), batches.map { it.size })
        assertEquals(addresses, batches.flatten())
    }

    @Test
    fun transactions_shortAddresses_stillTravelInOneRequest() {
        val addresses = addresses(count = 100, length = LEGACY_LENGTH, prefix = "1")

        assertEquals(1, capture(addresses).size)
    }

    @Test
    fun transactions_addressLongerThanTheBudget_isStillRequested() {
        val address = "bc1p" + "q".repeat(5_000)

        assertEquals(listOf(listOf(address)), capture(listOf(address)).map { it.requestedAddresses() })
    }

    @Test
    fun transactions_targetAtTheWidestPaginationOffset_staysWithinBudget() {
        val targets = capture(addresses(count = 100, length = TAPROOT_LENGTH, prefix = "bc1p"))

        assertTrue(targets.isNotEmpty())
        assertTrue(
            "widest paginated target was ${targets.widestPaginated().length} B",
            targets.widestPaginated().length <= MAX_REQUEST_TARGET
        )
    }

    @Test
    fun transactions_mixedLengthsOnAHostPathFillingTheReserve_staysWithinBudget() {
        // Addresses of one length hide a budget that is off by a few bytes, because the chunk
        // boundary only moves in whole addresses. Mixed lengths let it land anywhere.
        val addresses = addresses(count = 48, length = TAPROOT_LENGTH, prefix = "bc1p") +
            addresses(count = 18, length = BECH32_LENGTH, prefix = "bc1q")

        val targets = capture(addresses, basePath = "/" + "p".repeat(RESERVED_HOST_PATH - 1))

        assertTrue(targets.isNotEmpty())
        assertEquals(addresses, targets.flatMap { it.requestedAddresses() })
        targets.forEach {
            assertTrue("request target was ${it.length} B", it.length <= MAX_REQUEST_TARGET)
        }
        assertTrue(
            "widest paginated target was ${targets.widestPaginated().length} B",
            targets.widestPaginated().length <= MAX_REQUEST_TARGET
        )
    }

    /**
     * fetchTransactions re-requests the same chunk with a growing offset, so the target a chunk was
     * sized for is not the longest one it will ever emit.
     */
    private fun List<String>.widestPaginated(): String =
        requireNotNull(maxByOrNull { it.length }).substringBefore('?') +
            "?transaction_details=true&limit=10000&offset=${Int.MAX_VALUE}"

    private fun String.requestedAddresses(): List<String> =
        substringAfter("/dashboards/addresses/").substringBefore('?').split(",")

    private fun addresses(count: Int, length: Int, prefix: String): List<String> =
        (0 until count).map { index ->
            val body = index.toString().padStart(length - prefix.length, 'q')
            prefix + body
        }

    /** Runs a real restore scan against a local server and returns every request target it sent. */
    private fun capture(addresses: List<String>, basePath: String = HOST_BASE_PATH): List<String> {
        val server = MockWebServer()
        val targets = mutableListOf<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                request.path?.let { targets += it }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()

        try {
            // The production host carries a base path that ApiManager prepends and BlockchairApi
            // cannot see; a fixture rooted at "/" would hide a missing reserve for it.
            val host = server.url(basePath).toString()
            BlockchairApi("bitcoin", null, ApiManager(host)).transactions(addresses, null)
        } finally {
            server.shutdown()
        }

        return targets
    }

    private companion object {
        const val MAX_REQUEST_TARGET = 4000
        const val HOST_BASE_PATH = "/v1/blockchair"
        const val TAPROOT_LENGTH = 62
        const val BECH32_LENGTH = 42
        const val LEGACY_LENGTH = 34

        // Must equal BlockchairApi's HOST_BASE_PATH_RESERVE: the point of the mixed-length test is
        // that a host path large enough to consume the whole reserve still fits the budget.
        const val RESERVED_HOST_PATH = 128
    }
}

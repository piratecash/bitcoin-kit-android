package io.horizontalsystems.bitcoincore.apisync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ApiRequestWindowTest {

    @Test
    fun mapApiRequests_manyInputs_runsFourAtATime() = runBlocking(Dispatchers.IO) {
        val tracker = ConcurrencyTracker(expectedConcurrency = 4)

        val result = mapApiRequests((1..12).toList()) {
            tracker.track { it }
        }

        assertEquals((1..12).toList(), result)
        assertEquals(4, tracker.maximumConcurrency)
    }

    @Test
    fun mapApiRequests_requestFinishes_startsNextImmediately() = runBlocking(Dispatchers.IO) {
        val started = (1..5).associateWith { CompletableDeferred<Unit>() }
        val completions = (1..5).associateWith { CompletableDeferred<Int>() }

        val result = async {
            mapApiRequests((1..5).toList()) {
                started.getValue(it).complete(Unit)
                completions.getValue(it).await()
            }
        }

        (1..4).forEach {
            withTimeout(5_000) {
                started.getValue(it).await()
            }
        }
        completions.getValue(2).complete(2)

        withTimeout(5_000) {
            started.getValue(5).await()
        }
        assertFalse(completions.getValue(1).isCompleted)

        (1..5).forEach {
            completions.getValue(it).complete(it)
        }
        assertEquals((1..5).toList(), result.await())
    }

    @Test
    fun loadUntilConsecutiveEmpty_nonEmptyInputs_runsFourAtATime() = runBlocking(Dispatchers.IO) {
        val tracker = ConcurrencyTracker(expectedConcurrency = 4)

        val result = loadUntilConsecutiveEmpty((1..8).toList(), emptyLimit = 3) {
            tracker.track { listOf(it) }
        }

        assertEquals((1..8).toList(), result)
        assertEquals(4, tracker.maximumConcurrency)
    }

    @Test
    fun loadUntilConsecutiveEmpty_gapReached_doesNotStartMoreRequests() = runBlocking(Dispatchers.IO) {
        val started = (1..8).associateWith { CompletableDeferred<Unit>() }
        val completions = (1..8).associateWith { CompletableDeferred<List<Int>>() }

        val result = async {
            loadUntilConsecutiveEmpty((1..8).toList(), emptyLimit = 3) {
                started.getValue(it).complete(Unit)
                completions.getValue(it).await()
            }
        }

        (1..4).forEach {
            withTimeout(5_000) {
                started.getValue(it).await()
            }
        }

        completions.getValue(1).complete(emptyList())
        withTimeout(5_000) {
            started.getValue(5).await()
        }
        completions.getValue(2).complete(emptyList())
        withTimeout(5_000) {
            started.getValue(6).await()
        }
        completions.getValue(3).complete(emptyList())

        assertEquals(emptyList<Int>(), result.await())
        assertFalse(started.getValue(7).isCompleted)
        assertFalse(started.getValue(8).isCompleted)
    }

    @Test
    fun loadUntilConsecutiveEmpty_nonEmptyResult_resetsGapCounter() = runBlocking(Dispatchers.IO) {
        val result = loadUntilConsecutiveEmpty((1..8).toList(), emptyLimit = 4) {
            if (it == 2 || it == 6) listOf(it) else emptyList()
        }

        assertEquals(listOf(2, 6), result)
    }

    private class ConcurrencyTracker(expectedConcurrency: Int) {
        private val activeRequests = AtomicInteger()
        private val initialRequestsStarted = CountDownLatch(expectedConcurrency)
        private val maximum = AtomicInteger()

        val maximumConcurrency: Int
            get() = maximum.get()

        fun <T> track(request: () -> T): T {
            val active = activeRequests.incrementAndGet()
            maximum.updateAndGet { maxOf(it, active) }
            initialRequestsStarted.countDown()
            check(initialRequestsStarted.await(5, TimeUnit.SECONDS))

            return try {
                request()
            } finally {
                activeRequests.decrementAndGet()
            }
        }
    }
}

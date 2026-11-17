package io.horizontalsystems.bitcoincore.storage

import io.horizontalsystems.bitcoincore.models.PeerAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CoreDatabaseJvmTest {

    private lateinit var dataDir: File
    private lateinit var database: CoreDatabase

    @Before
    fun setUp() {
        dataDir = Files.createTempDirectory("bitcoincore-jvm").toFile()
        database = CoreDatabase.getInstance(dataDir.path, "core.db")
    }

    @After
    fun tearDown() {
        database.close()
        dataDir.deleteRecursively()
    }

    @Test
    fun getInstance_dataDir_createsDatabaseFileAndServesDao() {
        database.peerAddress.insertAll(listOf(PeerAddress("10.0.0.1"), PeerAddress("10.0.0.2")))

        assertTrue(File(dataDir, "core.db").exists())
        assertTrue(database.peerAddress.hasFresh(emptyList()))
        assertEquals("10.0.0.1", database.peerAddress.getLeastScoreFastest(listOf("10.0.0.2"))?.ip)
    }

    @Test
    fun inTransaction_blockingDaoCalls_commitsAllOfThem() {
        database.inTransaction {
            database.peerAddress.insertAll(listOf(PeerAddress("10.0.0.1")))
            database.peerAddress.setSuccessConnectionTime(time = 42L, ip = "10.0.0.1")
        }

        assertEquals(42L, database.peerAddress.getLeastScoreFastest(emptyList())?.connectionTime)
    }

    @Test
    fun inTransaction_bodyThrows_rollsBackEveryWrite() {
        database.peerAddress.insertAll(listOf(PeerAddress("10.0.0.1")))

        try {
            database.inTransaction {
                database.peerAddress.setSuccessConnectionTime(time = 42L, ip = "10.0.0.1")
                database.peerAddress.insertAll(listOf(PeerAddress("10.0.0.2")))
                throw IllegalStateException("boom")
            }
        } catch (e: IllegalStateException) {
            // expected: the body's failure is what triggers the rollback under test
        }

        assertNull(database.peerAddress.getLeastScoreFastest(emptyList())?.connectionTime)
        assertNull(database.peerAddress.getLeastScoreFastest(listOf("10.0.0.1")))
    }

    @Test(timeout = 30_000)
    fun inTransaction_secondWriterQueuedBehindHeldWriter_bothCommit() {
        val holderInside = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val secondEnteredCall = CountDownLatch(1)
        val secondDone = CountDownLatch(1)

        val holder = thread {
            database.inTransaction {
                database.peerAddress.insertAll(listOf(PeerAddress("10.0.0.1")))
                database.peerAddress.setSuccessConnectionTime(time = 1L, ip = "10.0.0.1")
                holderInside.countDown()
                releaseHolder.await()
            }
        }
        assertTrue(holderInside.await(10, TimeUnit.SECONDS))

        val second = thread {
            secondEnteredCall.countDown()
            database.inTransaction {
                database.peerAddress.insertAll(listOf(PeerAddress("10.0.0.2")))
                database.peerAddress.setSuccessConnectionTime(time = 2L, ip = "10.0.0.2")
            }
            secondDone.countDown()
        }
        assertTrue(secondEnteredCall.await(10, TimeUnit.SECONDS))
        // The writer is held, so the second transaction is blocked inside the call rather than slow to start.
        assertFalse(secondDone.await(500, TimeUnit.MILLISECONDS))

        releaseHolder.countDown()
        holder.join()
        second.join()

        assertEquals(1L, database.peerAddress.getLeastScoreFastest(listOf("10.0.0.2"))?.connectionTime)
        assertEquals(2L, database.peerAddress.getLeastScoreFastest(listOf("10.0.0.1"))?.connectionTime)
    }

    @Test(timeout = 30_000)
    fun inTransaction_callerInterruptedWhileQueued_stillCommitsAndRestoresFlag() {
        val holderInside = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val queuedEnteredCall = CountDownLatch(1)
        val queuedDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val flagAfterCall = AtomicBoolean(false)

        val holder = thread {
            database.inTransaction {
                database.peerAddress.insertAll(listOf(PeerAddress("10.0.0.1")))
                holderInside.countDown()
                releaseHolder.await()
            }
        }
        assertTrue(holderInside.await(10, TimeUnit.SECONDS))

        val queued = thread {
            try {
                queuedEnteredCall.countDown()
                database.inTransaction {
                    database.peerAddress.insertAll(listOf(PeerAddress("10.0.0.2")))
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
            flagAfterCall.set(Thread.currentThread().isInterrupted)
            queuedDone.countDown()
        }
        assertTrue(queuedEnteredCall.await(10, TimeUnit.SECONDS))
        assertFalse(queuedDone.await(500, TimeUnit.MILLISECONDS))

        queued.interrupt()
        releaseHolder.countDown()
        holder.join()
        queued.join()

        assertNull(failure.get())
        assertTrue(flagAfterCall.get())
        assertEquals("10.0.0.2", database.peerAddress.getLeastScoreFastest(listOf("10.0.0.1"))?.ip)
    }

    @Test(timeout = 30_000)
    fun inTransaction_callerInterruptedDuringBody_commitsAndRestoresFlag() {
        val bodyRunning = CountDownLatch(1)
        val interruptDelivered = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val flagAfterCall = AtomicBoolean(false)

        val writer = thread {
            try {
                database.inTransaction {
                    database.peerAddress.insertAll(listOf(PeerAddress("10.0.0.1")))
                    bodyRunning.countDown()
                    // Busy-wait, not sleep: the body must not consume the interrupt itself.
                    while (!interruptDelivered.get()) Thread.onSpinWait()
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
            flagAfterCall.set(Thread.currentThread().isInterrupted)
        }

        assertTrue(bodyRunning.await(10, TimeUnit.SECONDS))
        writer.interrupt()
        interruptDelivered.set(true)
        writer.join()

        assertNull(failure.get())
        assertTrue(flagAfterCall.get())
        assertEquals("10.0.0.1", database.peerAddress.getLeastScoreFastest(emptyList())?.ip)
    }
}

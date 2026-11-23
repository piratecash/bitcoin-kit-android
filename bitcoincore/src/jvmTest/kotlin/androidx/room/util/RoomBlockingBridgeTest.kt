package androidx.room.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RoomBlockingBridgeTest {

    @Test(timeout = 30_000)
    fun runBlockingUninterruptible_interruptedWhileBlockRuns_returnsResultAndRestoresFlag() {
        val blockRunning = CountDownLatch(1)
        val interruptDelivered = AtomicBoolean(false)
        val outcome = AtomicReference<Any?>()
        val failure = AtomicReference<Throwable?>()
        val flagAfterCall = AtomicBoolean(false)

        val caller = thread {
            try {
                // The block never suspends, so it completes inside the undispatched prefix.
                outcome.set(
                    runBlockingUninterruptible {
                        blockRunning.countDown()
                        while (!interruptDelivered.get()) Thread.onSpinWait()
                        "committed"
                    }
                )
            } catch (e: Throwable) {
                failure.set(e)
            }
            flagAfterCall.set(Thread.currentThread().isInterrupted)
        }

        assertTrue(blockRunning.await(10, TimeUnit.SECONDS))
        caller.interrupt()
        interruptDelivered.set(true)
        caller.join()

        assertNull(failure.get())
        assertEquals("committed", outcome.get())
        assertTrue(flagAfterCall.get())
    }
}

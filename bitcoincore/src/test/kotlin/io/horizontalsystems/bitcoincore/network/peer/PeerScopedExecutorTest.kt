package io.horizontalsystems.bitcoincore.network.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PeerScopedExecutor centralises the executor lifecycle previously duplicated in
 * InitialBlockDownload, BlockDownload, and three MasternodeListSyncer variants.
 * Late peer callbacks (onPeerConnect / onPeerDisconnect / onPeerSynced) routinely
 * fire after close(), so execute() must drop those tasks silently rather than
 * propagate RejectedExecutionException up the dispatcher.
 */
class PeerScopedExecutorTest {

    @Test
    fun executesSubmittedTask() {
        val executor = PeerScopedExecutor()
        val ran = CountDownLatch(1)

        executor.execute { ran.countDown() }

        assertTrue(
            "execute() must dispatch the task to the underlying worker.",
            ran.await(2, TimeUnit.SECONDS)
        )
        executor.close()
    }

    @Test
    fun executeAfterCloseIsSilentDrop() {
        val executor = PeerScopedExecutor()
        executor.close()

        var ran = 0
        // Must NOT throw RejectedExecutionException — late peer callbacks must
        // degrade silently.
        executor.execute { ran++ }

        assertEquals("Late tasks must be dropped, not executed.", 0, ran)
    }

    @Test
    fun startAfterCloseRecreatesUnderlyingExecutor() {
        val executor = PeerScopedExecutor()
        executor.close()
        assertTrue(executor.isShutdown)

        executor.start()
        assertFalse("After start() the executor must accept tasks again.", executor.isShutdown)

        val ran = CountDownLatch(1)
        executor.execute { ran.countDown() }
        assertTrue(ran.await(2, TimeUnit.SECONDS))
        executor.close()
    }

    @Test
    fun closeIsIdempotent() {
        val executor = PeerScopedExecutor()
        executor.close()
        executor.close() // must not throw
        assertTrue(executor.isShutdown)
    }

    @Test
    fun queuedTaskAfterCloseDoesNotRun() {
        // Soft shutdown() would let already-queued tasks finish even after
        // close(), which can race with a new start() and mutate kit state
        // that has already been torn down. close() must drop pending work so
        // the next start() begins with a clean slate.
        val executor = PeerScopedExecutor()
        val releaseWorker = CountDownLatch(1)
        val firstTaskEntered = CountDownLatch(1)
        val queuedTaskRan = AtomicBoolean(false)

        // Hog the single worker thread so the next task is forced into the queue.
        executor.execute {
            firstTaskEntered.countDown()
            releaseWorker.await()
        }
        assertTrue(firstTaskEntered.await(2, TimeUnit.SECONDS))

        // Queued behind the blocking task — must not run after close().
        executor.execute { queuedTaskRan.set(true) }

        executor.close()
        // Let the hogging task complete so the worker has a chance to drain.
        releaseWorker.countDown()

        // Give the executor ample time to (incorrectly) run the queued task.
        Thread.sleep(300)

        assertFalse(
            "Queued tasks must be dropped on close(); otherwise late writes " +
                "can collide with a fresh start() and corrupt kit state.",
            queuedTaskRan.get()
        )
    }

    @Test
    fun startWhenNotClosedKeepsSameExecutor() {
        val executor = PeerScopedExecutor()
        // start() before any close() is a no-op — must not throw away the
        // running underlying executor or interrupt pending work.
        executor.start()
        executor.start()
        assertFalse(executor.isShutdown)
        executor.close()
    }
}

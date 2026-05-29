package io.horizontalsystems.bitcoincore.network.peer

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Single-threaded executor whose lifecycle is bound to a kit's relationship
 * with a [PeerGroup]. Centralises three concerns that used to be duplicated
 * across InitialBlockDownload / BlockDownload / MasternodeListSyncer:
 *
 *  1. The underlying [ExecutorService] is recreated on [start] after a
 *     previous [close], because a shut-down executor cannot be reused.
 *  2. [close] is idempotent — both [PeerGroup.Listener.onStop] and the
 *     explicit teardown from BitcoinCore.stop() may call it.
 *  3. [execute] silently drops late tasks instead of propagating
 *     `RejectedExecutionException` to the caller thread. Late callbacks are
 *     normal: [PeerGroup.stop] signals peers to disconnect but
 *     `PeerConnection.run` unwinds asynchronously, so `onPeerDisconnect`
 *     and friends can still arrive after the kit has been torn down.
 */
class PeerScopedExecutor : AutoCloseable {

    @Volatile
    private var delegate: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Re-arms the executor if it was previously [close]d. No-op when the
     * underlying executor is still alive.
     */
    @Synchronized
    fun start() {
        if (delegate.isShutdown) {
            delegate = Executors.newSingleThreadExecutor()
        }
    }

    /**
     * Submits a task. After [close] the task is dropped silently — late peer
     * callbacks must not crash their dispatcher.
     */
    fun execute(task: Runnable) {
        try {
            delegate.execute(task)
        } catch (_: RejectedExecutionException) {
            // Intentional: late task after close().
        }
    }

    @Synchronized
    override fun close() {
        // shutdownNow() (not shutdown()) so that tasks already sitting in the
        // queue are dropped, not allowed to drain after teardown. Without this
        // a queued late peer callback could run after close() and race a
        // subsequent start() — both executors would mutate the kit's state
        // concurrently. The currently running task (if any) is allowed to
        // finish naturally; our peer-scoped tasks are short, synchronous, and
        // don't block on interruptible operations, so the interrupt flag the
        // JDK sets is effectively a no-op for them.
        delegate.shutdownNow()
    }

    val isShutdown: Boolean
        get() = delegate.isShutdown
}

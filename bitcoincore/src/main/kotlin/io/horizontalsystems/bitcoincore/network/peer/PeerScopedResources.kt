package io.horizontalsystems.bitcoincore.network.peer

/**
 * Closes per-kit resources held by [PeerGroup.Listener]s that own private
 * worker threads (e.g. `peersQueue` inside InitialBlockDownload /
 * BlockDownload / MasternodeListSyncer).
 *
 * Required because [SharedPeerGroup.stop] only invokes `super.stop()` —
 * and therefore `Listener.onStop()` — when its ref count reaches zero. When
 * a non-last kit calls `BitcoinCore.stop()` its listeners are removed from
 * the shared group **before** `onStop()` has a chance to run, leaking their
 * thread pools forever. Walking the kit-scoped listener collection and
 * closing every [AutoCloseable] explicitly guarantees teardown regardless
 * of the surrounding PeerGroup variant.
 */
fun closePeerScopedResources(listeners: Collection<PeerGroup.Listener>) {
    listeners.filterIsInstance<AutoCloseable>().forEach { closeable ->
        try {
            closeable.close()
        } catch (_: Exception) {
            // Best-effort: a failure in one listener must not prevent the
            // remaining listeners from releasing their resources.
        }
    }
}

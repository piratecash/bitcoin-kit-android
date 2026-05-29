package io.horizontalsystems.bitcoincore

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Static-analysis invariant: every PeerGroup.Listener registered by the
 * builder must go through [BitcoinCore.addPeerGroupListener] so it ends up in
 * BitcoinCore.registeredPeerGroupListeners. Without this, `unregisterFromSharedGroup`
 * cannot detach the listener when the kit stops, and on a SharedPeerGroup the
 * stopped (non-last) kit's listeners stay live — holding the kit graph alive
 * and continuing to receive peer callbacks.
 *
 * Why a source-level test rather than a runtime one: BitcoinCoreBuilder has no
 * lightweight integration harness in this repo, and building a real BitcoinCore
 * requires dozens of platform-specific dependencies. The contract being
 * defended ("don't register peer listeners outside BitcoinCore's tracking")
 * is structural and is easiest to enforce structurally.
 */
class BitcoinCoreBuilderInvariantTest {

    @Test
    fun builderRegistersAllPeerGroupListenersThroughBitcoinCore() {
        val source = File(BUILDER_RELATIVE_PATH).readText()

        val untrackedRegistrations = Regex("""\bpeerGroup\.addPeerGroupListener\b""")
            .findAll(source)
            .count()

        assertEquals(
            "Every peer-group listener registration in BitcoinCoreBuilder must use " +
                "`bitcoinCore.addPeerGroupListener(...)` so it lands in " +
                "registeredPeerGroupListeners and is unregistered on stop(). Found " +
                "$untrackedRegistrations direct `peerGroup.addPeerGroupListener` call(s).",
            0,
            untrackedRegistrations
        )
    }

    private companion object {
        const val BUILDER_RELATIVE_PATH =
            "src/main/kotlin/io/horizontalsystems/bitcoincore/BitcoinCoreBuilder.kt"
    }
}

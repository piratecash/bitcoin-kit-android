package io.horizontalsystems.bitcoincore.network.peer

import io.horizontalsystems.bitcoincore.managers.BloomFilterManager
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock

class SharedPeerGroupHolderDatabaseKeyTest {
    @Test
    fun requireDatabaseKey_sameKeyContent_acceptsKey() {
        val key = ByteArray(32) { it.toByte() }
        val holder = holder(key)

        holder.requireDatabaseKey(key.copyOf())
    }

    @Test
    fun requireDatabaseKey_differentKeyOrMode_rejectsKey() {
        val holder = holder(ByteArray(32))

        assertThrows(IllegalArgumentException::class.java) {
            holder.requireDatabaseKey(ByteArray(32) { 1 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            holder.requireDatabaseKey(null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            holder(null).requireDatabaseKey(ByteArray(32))
        }
    }

    private fun holder(databaseKey: ByteArray?): SharedPeerGroupHolder = SharedPeerGroupHolder(
        peerGroup = mock<SharedPeerGroup>(),
        peerManager = mock<PeerManager>(),
        bloomFilterManager = mock<BloomFilterManager>(),
        networkMessageParser = mock<NetworkMessageParser>(),
        networkMessageSerializer = mock<NetworkMessageSerializer>(),
        databaseKey = databaseKey,
    )
}

package io.horizontalsystems.litecoinkit.mweb.address

import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.mweb.MwebSyncState
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonClient
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebDaemonStatus
import io.horizontalsystems.litecoinkit.mweb.daemon.MwebCreateResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.Closeable

class MwebAddressPoolTest {
    private val codec = MwebAddressCodec(LitecoinKit.NetworkType.MainNet)
    private val daemonClient = FakeMwebDaemonClient(codec)
    private val storage = InMemoryMwebAddressStorage()
    private val addressPool = MwebAddressPool(codec, daemonClient, storage)

    @Test
    fun receiveAddress_usesIndexOne() {
        val address = addressPool.receiveAddress()

        assertEquals(daemonClient.addressAt(1), address)
        assertEquals(listOf(1 to 1), daemonClient.addressRequests)
    }

    @Test
    fun changeAddress_usesIndexZero() {
        val address = addressPool.changeAddress()

        assertEquals(daemonClient.addressAt(0), address)
        assertEquals(listOf(0 to 0), daemonClient.addressRequests)
    }

    @Test
    fun addresses_changeAndFirstReceiveRange_requestsInclusiveRange() {
        val addresses = addressPool.addresses(0, 1)

        assertEquals(listOf(daemonClient.addressAt(0), daemonClient.addressAt(1)), addresses)
        assertEquals(listOf(0 to 1), daemonClient.addressRequests)
    }

    @Test
    fun addresses_cachedRange_requestsOnlyMissingTail() {
        addressPool.receiveAddress()
        val addresses = addressPool.addresses(1, 3)

        assertEquals(listOf(daemonClient.addressAt(1), daemonClient.addressAt(2), daemonClient.addressAt(3)), addresses)
        assertEquals(listOf(1 to 1, 2 to 3), daemonClient.addressRequests)
    }

    private class FakeMwebDaemonClient(
        private val codec: MwebAddressCodec,
    ) : MwebDaemonClient {
        val addressRequests = mutableListOf<Pair<Int, Int>>()

        override fun start(statusTimeoutMillis: Long): MwebDaemonStatus = status()

        override fun stop() = Unit

        override fun status(statusTimeoutMillis: Long): MwebDaemonStatus {
            return MwebDaemonStatus(MwebSyncState(0, 0, 0), nativeVersion = "test")
        }

        override fun addresses(fromIndex: Int, toIndex: Int): List<String> {
            addressRequests.add(fromIndex to toIndex)
            return (fromIndex..toIndex).map { addressAt(it) }
        }

        override fun utxos(fromHeight: Int, onUtxo: (MwebUtxo) -> Unit, onError: (Throwable) -> Unit): Closeable {
            return Closeable { }
        }

        override fun spent(outputIds: List<String>): List<String> = emptyList()

        override fun create(rawTransaction: ByteArray, feeRate: Int, dryRun: Boolean): MwebCreateResult {
            return MwebCreateResult(rawTransaction, emptyList())
        }

        override fun broadcast(rawTransaction: ByteArray): String {
            return "transaction-hash"
        }

        fun addressAt(index: Int): String {
            val scanPublicKey = ByteArray(33) { offset -> (index + offset + 1).toByte() }
            val spendPublicKey = ByteArray(33) { offset -> (index + offset + 34).toByte() }
            return codec.encode(scanPublicKey, spendPublicKey)
        }
    }
}

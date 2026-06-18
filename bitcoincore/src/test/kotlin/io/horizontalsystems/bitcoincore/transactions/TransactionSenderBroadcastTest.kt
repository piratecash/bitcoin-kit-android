package io.horizontalsystems.bitcoincore.transactions

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.BitcoinCore.SendType
import io.horizontalsystems.bitcoincore.Fixtures
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairApi
import io.horizontalsystems.bitcoincore.blocks.InitialBlockDownload
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.models.RawTransactionBroadcastStatus
import io.horizontalsystems.bitcoincore.models.SentTransaction
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.network.peer.task.SendTransactionTask
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TransactionSenderBroadcastTest {

    private val transaction: FullTransaction = Fixtures.transactionP2WPKH

    private lateinit var transactionSyncer: TransactionSyncer
    private lateinit var peerManager: PeerManager
    private lateinit var initialBlockDownload: InitialBlockDownload
    private lateinit var storage: IStorage
    private lateinit var timer: TransactionSendTimer
    private lateinit var blockchairApi: BlockchairApi
    private lateinit var readyPeer: Peer

    @Before
    fun setup() {
        transactionSyncer = mock()
        peerManager = mock()
        initialBlockDownload = mock()
        storage = mock()
        timer = mock()
        blockchairApi = mock()
        readyPeer = mock {
            on { ready } doReturn true
            on { host } doReturn "0.0.0.1"
        }
        whenever(storage.getExternalSentTransactions()).thenReturn(emptyList())
    }

    @Test
    fun broadcastRawTransaction_p2pWithPeers_relaysToPeerAndQueuesExternalRetry() = runBlocking {
        givenPeersAvailable()
        val captor = argumentCaptor<SentTransaction>()

        val status = sender(SendType.P2P).broadcastRawTransaction(transaction, RAW_HEX)

        verify(readyPeer).addTask(any<SendTransactionTask>())
        verify(storage).addSentTransaction(captor.capture())

        assertEquals(RawTransactionBroadcastStatus.Submitted, status)
        val sentTransaction = captor.firstValue
        assertTrue(sentTransaction.external)
        assertEquals(RAW_HEX, sentTransaction.rawTransactionHex)
    }

    @Test
    fun broadcastRawTransaction_p2pNoPeers_queuesRetryWithoutThrowing() = runBlocking {
        givenNoPeers()
        val captor = argumentCaptor<SentTransaction>()

        val status = sender(SendType.P2P).broadcastRawTransaction(transaction, RAW_HEX)

        verify(storage).addSentTransaction(captor.capture())
        verify(timer).startIfNotRunning()
        assertEquals(RawTransactionBroadcastStatus.Queued, status)
        assertTrue(captor.lastValue.external)
        assertEquals(RAW_HEX, captor.lastValue.rawTransactionHex)
    }

    @Test
    fun broadcastRawTransaction_apiAccepts_pushesViaApiWithoutPeerRelay() = runBlocking {
        givenPeersAvailable()

        val status = sender(SendType.API(blockchairApi)).broadcastRawTransaction(transaction, RAW_HEX)

        verify(blockchairApi).broadcastTransaction(RAW_HEX)
        verify(readyPeer, never()).addTask(any<SendTransactionTask>())
        assertEquals(RawTransactionBroadcastStatus.Submitted, status)
    }

    @Test
    fun broadcastRawTransaction_apiFails_fallsBackToPeerRelay() = runBlocking {
        givenPeersAvailable()
        whenever(blockchairApi.broadcastTransaction(RAW_HEX)).thenThrow(RuntimeException("api down"))

        val status = sender(SendType.API(blockchairApi)).broadcastRawTransaction(transaction, RAW_HEX)

        verify(blockchairApi).broadcastTransaction(RAW_HEX)
        verify(readyPeer).addTask(any<SendTransactionTask>())
        assertEquals(RawTransactionBroadcastStatus.Submitted, status)
    }

    @Test
    fun broadcastRawTransaction_apiCancelled_propagatesAndSkipsPeerRelay() {
        givenPeersAvailable()
        whenever(blockchairApi.broadcastTransaction(RAW_HEX)).thenThrow(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking { sender(SendType.API(blockchairApi)).broadcastRawTransaction(transaction, RAW_HEX) }
        }

        verify(readyPeer, never()).addTask(any<SendTransactionTask>())
    }

    @Test
    fun sendPendingTransactions_externalRawTx_retriesViaP2P() {
        val sentTransaction = SentTransaction(transaction.header.hash, RAW_HEX).apply {
            lastSendTime = 0
        }
        givenPeersAvailable()
        whenever(transactionSyncer.getNewTransactions()).thenReturn(emptyList())
        whenever(storage.getExternalSentTransactions()).thenReturn(listOf(sentTransaction))
        whenever(storage.getSentTransaction(transaction.header.hash)).thenReturn(sentTransaction)

        sender(SendType.P2P).sendPendingTransactions()

        verify(readyPeer).addTask(any<SendTransactionTask>())
        verify(storage).updateSentTransaction(sentTransaction)
    }

    @Test
    fun sendPendingTransactions_externalRawTxWithMissingHex_dropsQueueItem() {
        val sentTransaction = SentTransaction(transaction.header.hash).apply {
            external = true
            rawTransactionHex = null
        }
        whenever(transactionSyncer.getNewTransactions()).thenReturn(emptyList())
        whenever(storage.getExternalSentTransactions()).thenReturn(listOf(sentTransaction))

        sender(SendType.P2P).sendPendingTransactions()

        verify(storage).deleteSentTransaction(sentTransaction)
        verify(readyPeer, never()).addTask(any<SendTransactionTask>())
    }

    @Test
    fun transactionsRelayed_externalTx_deletesSentTransaction() {
        val sentTransaction = SentTransaction(transaction.header.hash, RAW_HEX)
        whenever(storage.getSentTransaction(transaction.header.hash)).thenReturn(sentTransaction)

        val sender = sender(SendType.P2P)

        sender.transactionsRelayed(listOf(transaction))

        verify(storage).deleteSentTransaction(sentTransaction)
    }

    @Test
    fun handleCompletedTask_untrackedForeignTxRequestedByPeer_recordsNoDiagnosticsOrRetries() {
        val sender = sender(SendType.P2P)
        val task = SendTransactionTask(transaction).apply {
            completionReason = SendTransactionTask.CompletionReason.REQUESTED_BY_PEER
            owner = sender
        }

        val handled = sender.handleCompletedTask(readyPeer, task)

        assertTrue(handled)
        verify(storage, never()).addSentTransaction(any())
        verify(storage, never()).updateSentTransaction(any())
        verify(storage, never()).deleteSentTransaction(any())
        assertTrue(
            "Foreign transaction must not leave in-memory diagnostics entries",
            broadcastDiagnosticsOf(sender).isEmpty()
        )
    }

    @Test
    fun handleCompletedTask_externalTxRetriesExhausted_deletesWithoutInvalidatingTransaction() {
        val sentTransaction = SentTransaction(transaction.header.hash, RAW_HEX).apply {
            retriesCount = 9
            sendSuccess = false
        }
        val sender = sender(SendType.P2P)
        val task = SendTransactionTask(transaction).apply {
            completionReason = SendTransactionTask.CompletionReason.TIMEOUT
            owner = sender
        }

        whenever(storage.getSentTransaction(transaction.header.hash)).thenReturn(sentTransaction)

        sender.handleCompletedTask(readyPeer, task)

        verify(transactionSyncer, never()).handleInvalid(transaction)
        verify(storage).deleteSentTransaction(sentTransaction)
    }

    private fun sender(sendType: SendType) = TransactionSender(
        transactionSyncer,
        peerManager,
        initialBlockDownload,
        storage,
        timer,
        BaseTransactionSerializer(),
        sendType,
        allowBroadcastFromUnsyncedPeers = true,
    )

    private fun broadcastDiagnosticsOf(sender: TransactionSender): Map<*, *> {
        return TransactionSender::class.java
            .getDeclaredField("broadcastDiagnostics")
            .apply { isAccessible = true }
            .get(sender) as Map<*, *>
    }

    private fun givenPeersAvailable() {
        whenever(peerManager.peersCount).thenReturn(2)
        whenever(peerManager.readyPears()).thenReturn(listOf(readyPeer))
        whenever(initialBlockDownload.syncedPeers).thenReturn(CopyOnWriteArrayList())
    }

    private fun givenNoPeers() {
        whenever(peerManager.peersCount).thenReturn(0)
        whenever(peerManager.readyPears()).thenReturn(emptyList())
        whenever(initialBlockDownload.syncedPeers).thenReturn(CopyOnWriteArrayList())
    }

    private companion object {
        val RAW_HEX = BaseTransactionSerializer().serialize(Fixtures.transactionP2WPKH).toHexString()
    }
}

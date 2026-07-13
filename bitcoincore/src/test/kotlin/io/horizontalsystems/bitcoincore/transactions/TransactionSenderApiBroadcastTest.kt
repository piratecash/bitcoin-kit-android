package io.horizontalsystems.bitcoincore.transactions

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.inOrder
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.BitcoinCore.SendType
import io.horizontalsystems.bitcoincore.Fixtures
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairApi
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.blocks.InitialBlockDownload
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.models.InventoryItem
import io.horizontalsystems.bitcoincore.models.SentTransaction
import io.horizontalsystems.bitcoincore.network.messages.GetDataMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.messages.TransactionMessage
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.bitcoincore.network.peer.task.SendTransactionTask
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TransactionSenderApiBroadcastTest {

    private lateinit var transactionSender: TransactionSender
    private lateinit var transactionSyncer: TransactionSyncer
    private lateinit var peerManager: PeerManager
    private lateinit var initialBlockDownload: InitialBlockDownload
    private lateinit var storage: IStorage
    private lateinit var timer: TransactionSendTimer
    private lateinit var blockchairApi: BlockchairApi

    @Before
    fun setup() {
        transactionSyncer = mock()
        peerManager = mock()
        initialBlockDownload = mock()
        storage = mock()
        timer = mock()
        blockchairApi = mock()

        whenever(storage.getExternalSentTransactions()).thenReturn(emptyList())
        // Own transaction is tracked and unmined by default, so SendTransactionTask.isStillBroadcastable
        // (re-checked at getdata-serve time) passes unless a test explicitly overrides it.
        whenever(storage.getTransaction(any())).thenReturn(Fixtures.transactionP2WPKH.header)
        // Own transaction is pending by default, so the atomic guard that gates every own-transaction
        // send passes unless a test explicitly overrides it (e.g. to simulate a concurrent expiry delete).
        whenever(storage.recordBroadcastAttemptIfPending(any())).thenReturn(true)
        transactionSender = TransactionSender(
            transactionSyncer,
            peerManager,
            initialBlockDownload,
            storage,
            timer,
            BaseTransactionSerializer(),
            SendType.API(blockchairApi),
            allowBroadcastFromUnsyncedPeers = true,
            // Unconfined makes the fire-and-forget API broadcast coroutine run synchronously
            // (nothing in the call chain suspends), so assertions below need no async barrier.
            coroutineDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun sendPendingTransactions_apiFailsAndNoPeers_queuesRetryWithoutInvalidatingTransaction() {
        val transaction = Fixtures.transactionP2WPKH
        val sentTransactionCaptor = argumentCaptor<SentTransaction>()

        whenever(transactionSyncer.getNewTransactions()).thenReturn(listOf(transaction))
        whenever(blockchairApi.broadcastTransaction(any())).thenThrow(RuntimeException("timeout"))
        whenever(peerManager.peersCount).thenReturn(0)
        whenever(peerManager.readyPears()).thenReturn(emptyList())
        whenever(initialBlockDownload.syncedPeers).thenReturn(CopyOnWriteArrayList())
        whenever(storage.getSentTransaction(transaction.header.hash)).thenReturn(null)

        transactionSender.sendPendingTransactions()

        verify(storage).recordBroadcastAttemptIfPending(sentTransactionCaptor.capture())
        verify(timer).startIfNotRunning()
        verify(transactionSyncer, never()).handleInvalid(transaction)
        verify(transactionSyncer, never()).handleRelayed(any())

        val sentTransaction = sentTransactionCaptor.firstValue
        assertEquals(0, sentTransaction.retriesCount)
        assertFalse(sentTransaction.sendSuccess)
    }

    @Test
    fun sendPendingTransactions_apiFailsWithPeerAvailable_p2pFallbackActuallySendsTransaction() {
        val transaction = Fixtures.transactionP2WPKH
        val hash = transaction.header.hash
        val peer = mock<Peer> {
            on { ready } doReturn true
            on { host } doReturn "peer0"
        }
        val taskCaptor = argumentCaptor<SendTransactionTask>()

        whenever(transactionSyncer.getNewTransactions()).thenReturn(listOf(transaction))
        whenever(blockchairApi.broadcastTransaction(any())).thenThrow(RuntimeException("api down"))
        whenever(peerManager.peersCount).thenReturn(2)
        whenever(peerManager.readyPears()).thenReturn(listOf(peer))
        whenever(initialBlockDownload.syncedPeers).thenReturn(CopyOnWriteArrayList())
        whenever(storage.getSentTransaction(hash)).thenReturn(null)

        transactionSender.sendPendingTransactions()

        verify(peer).addTask(taskCaptor.capture())
        val requester = FakeRequester()
        taskCaptor.firstValue.requester = requester
        taskCaptor.firstValue.handleMessage(GetDataMessage(listOf(InventoryItem(InventoryItem.MSG_TX, hash))))

        assertTrue(
            "P2P fallback task must actually send the transaction bytes, not suppress them",
            requester.sentMessages.any { it is TransactionMessage }
        )
    }

    @Test
    fun sendPendingTransactions_apiOwnTransactionDeletedConcurrently_skipsBroadcastWithoutOrphaningSentTransaction() {
        val transaction = Fixtures.transactionP2WPKH

        whenever(transactionSyncer.getNewTransactions()).thenReturn(listOf(transaction))
        // Simulates a concurrent NEW-expiry delete winning the race: the atomic conditional insert
        // finds the transaction is no longer pending and writes nothing.
        whenever(storage.recordBroadcastAttemptIfPending(any())).thenReturn(false)

        transactionSender.sendPendingTransactions()

        verify(blockchairApi, never()).broadcastTransaction(any())
        verify(storage, never()).addSentTransaction(any())
        verify(storage, never()).updateSentTransaction(any())
        verify(transactionSyncer, never()).handleRelayed(any())
    }

    @Test
    fun sendPendingTransactions_apiOwnTransactionSuccess_deletesSentTransactionRowAfterAccept() {
        val transaction = Fixtures.transactionP2WPKH
        val sentTransaction = SentTransaction(transaction.header.hash).apply {
            lastSendTime = 0
        }

        whenever(transactionSyncer.getNewTransactions()).thenReturn(listOf(transaction))
        whenever(storage.getSentTransaction(transaction.header.hash)).thenReturn(sentTransaction)

        transactionSender.sendPendingTransactions()

        verify(blockchairApi).broadcastTransaction(any())
        // The pre-accept queue row must not linger after a successful accept, otherwise it would
        // wrongly keep showing up in getTransactionsInSendQueue for an already-relayed transaction.
        verify(storage).deleteSentTransaction(sentTransaction)
        verify(transactionSyncer).handleRelayed(listOf(transaction))
    }

    @Test
    fun sendPendingTransactions_apiOwnTransactionSuccess_relaysBeforeDeletingSentTransactionRow() {
        // While status is still NEW, only the SentTransaction row blocks a concurrent NEW-expiry
        // delete. If the row were removed before the transaction is marked RELAYED (or handleRelayed
        // never ran because it threw), the transaction would sit NEW and unguarded. handleRelayed
        // must therefore complete before the row is removed.
        val transaction = Fixtures.transactionP2WPKH
        val sentTransaction = SentTransaction(transaction.header.hash).apply {
            lastSendTime = 0
        }

        whenever(transactionSyncer.getNewTransactions()).thenReturn(listOf(transaction))
        whenever(storage.getSentTransaction(transaction.header.hash)).thenReturn(sentTransaction)

        transactionSender.sendPendingTransactions()

        inOrder(transactionSyncer, storage) {
            verify(transactionSyncer).handleRelayed(listOf(transaction))
            verify(storage).deleteSentTransaction(sentTransaction)
        }
    }

    @Test
    fun sendPendingTransactions_p2pMultiplePeersSelected_everySelectedPeerServesGetData() {
        val transaction = Fixtures.transactionP2WPKH
        val hash = transaction.header.hash
        val peers = (0 until 4).map { index ->
            mock<Peer> {
                on { ready } doReturn true
                on { host } doReturn "peer$index"
            }
        }
        val taskCaptor = argumentCaptor<SendTransactionTask>()

        whenever(transactionSyncer.getNewTransactions()).thenReturn(listOf(transaction))
        whenever(peerManager.peersCount).thenReturn(peers.size)
        whenever(peerManager.readyPears()).thenReturn(peers)
        whenever(initialBlockDownload.syncedPeers).thenReturn(CopyOnWriteArrayList())
        whenever(storage.getSentTransaction(hash)).thenReturn(null)

        val p2pSender = TransactionSender(
            transactionSyncer,
            peerManager,
            initialBlockDownload,
            storage,
            timer,
            BaseTransactionSerializer(),
            SendType.P2P,
            allowBroadcastFromUnsyncedPeers = true,
        )

        // P2P sends run synchronously (unlike the API path), so all addTask calls have already
        // happened once this returns - no async barrier needed.
        p2pSender.sendPendingTransactions()

        val selectedPeers = peers.take(peers.size / 2)
        assertEquals(2, selectedPeers.size)
        selectedPeers.forEach { peer -> verify(peer).addTask(taskCaptor.capture()) }

        val requesters = taskCaptor.allValues.map { task ->
            FakeRequester().also { requester ->
                task.requester = requester
                task.handleMessage(GetDataMessage(listOf(InventoryItem(InventoryItem.MSG_TX, hash))))
            }
        }

        assertTrue(
            "Every selected peer must be served the transaction, not just the first",
            requesters.all { requester -> requester.sentMessages.any { it is TransactionMessage } }
        )
    }

    @Test
    fun sendPendingTransactions_externalRawTx_apiSuccess_broadcastsOriginalHexAndDeletesQueueItem() {
        val transaction = Fixtures.transactionP2WPKH
        val sentTransaction = SentTransaction(transaction.header.hash, RAW_HEX).apply {
            lastSendTime = 0
        }

        whenever(transactionSyncer.getNewTransactions()).thenReturn(emptyList())
        whenever(storage.getExternalSentTransactions()).thenReturn(listOf(sentTransaction))
        whenever(storage.getSentTransaction(transaction.header.hash)).thenReturn(sentTransaction)

        transactionSender.sendPendingTransactions()

        verify(blockchairApi).broadcastTransaction(RAW_HEX)
        verify(storage).deleteSentTransaction(sentTransaction)
        verify(transactionSyncer, never()).handleRelayed(any())
    }

    @Test
    fun handleCompletedTask_p2pRetriesExhausted_invalidatesTransaction() {
        val transaction = Fixtures.transactionP2WPKH
        val sentTransaction = SentTransaction(transaction.header.hash).apply {
            retriesCount = 9
            sendSuccess = false
        }
        val task = SendTransactionTask(transaction).apply {
            completionReason = SendTransactionTask.CompletionReason.TIMEOUT
        }
        val peer = mock<Peer>()

        whenever(storage.getSentTransaction(transaction.header.hash)).thenReturn(sentTransaction)

        transactionSender.handleCompletedTask(peer, task)

        verify(transactionSyncer).handleInvalid(transaction)
        verify(storage).deleteSentTransaction(sentTransaction)
    }

    private class FakeRequester : PeerTask.Requester {
        override val protocolVersion: Int = 0
        val sentMessages = mutableListOf<IMessage>()

        override fun send(message: IMessage) {
            sentMessages += message
        }
    }

    private companion object {
        val RAW_HEX = BaseTransactionSerializer().serialize(Fixtures.transactionP2WPKH).toHexString()
    }
}

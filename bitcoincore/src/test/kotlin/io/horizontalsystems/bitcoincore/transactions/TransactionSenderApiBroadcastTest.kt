package io.horizontalsystems.bitcoincore.transactions

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.BitcoinCore.SendType
import io.horizontalsystems.bitcoincore.Fixtures
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairApi
import io.horizontalsystems.bitcoincore.blocks.InitialBlockDownload
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.models.SentTransaction
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.network.peer.task.SendTransactionTask
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.timeout
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

        transactionSender = TransactionSender(
            transactionSyncer,
            peerManager,
            initialBlockDownload,
            storage,
            timer,
            BaseTransactionSerializer(),
            SendType.API(blockchairApi),
            allowBroadcastFromUnsyncedPeers = true,
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

        verify(storage, timeout(1_000)).addSentTransaction(sentTransactionCaptor.capture())
        verify(timer, timeout(1_000)).startIfNotRunning()
        verify(transactionSyncer, never()).handleInvalid(transaction)
        verify(transactionSyncer, never()).handleRelayed(any())

        val sentTransaction = sentTransactionCaptor.firstValue
        assertEquals(0, sentTransaction.retriesCount)
        assertFalse(sentTransaction.sendSuccess)
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
}

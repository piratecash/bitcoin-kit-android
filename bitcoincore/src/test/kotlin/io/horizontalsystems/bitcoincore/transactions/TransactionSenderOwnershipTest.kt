package io.horizontalsystems.bitcoincore.transactions

import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.BitcoinCore.SendType
import io.horizontalsystems.bitcoincore.blocks.InitialBlockDownload
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.models.SentTransaction
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.network.messages.RejectMessage
import io.horizontalsystems.bitcoincore.network.peer.Peer
import io.horizontalsystems.bitcoincore.network.peer.PeerManager
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.bitcoincore.network.peer.task.SendTransactionTask
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransactionSenderOwnershipTest {

    private lateinit var transactionSender: TransactionSender
    private lateinit var peer: Peer
    private lateinit var storage: IStorage

    @Before
    fun setup() {
        peer = mock { on { ready } doReturn true }
        storage = mock()
        transactionSender = TransactionSender(
            mock<TransactionSyncer>(),
            mock<PeerManager>(),
            mock<InitialBlockDownload>(),
            storage,
            mock<TransactionSendTimer>(),
            mock(),
            SendType.P2P,
            allowBroadcastFromUnsyncedPeers = true
        )
    }

    private fun mockSendTransactionTask(owner: Any?): SendTransactionTask {
        val header = mock<Transaction> {
            on { hash } doReturn ByteArray(32)
        }
        val transaction = mock<FullTransaction> {
            on { this.header } doReturn header
        }
        val task = mock<SendTransactionTask> {
            on { this.transaction } doReturn transaction
        }
        whenever(task.owner).thenReturn(owner)
        return task
    }

    @Test
    fun `handleCompletedTask - null owner - handled for SendTransactionTask`() {
        val task = mockSendTransactionTask(null)

        val result = transactionSender.handleCompletedTask(peer, task)
        assertTrue(result)
    }

    @Test
    fun `handleCompletedTask - owner is this sender - handled`() {
        val task = mockSendTransactionTask(transactionSender)

        val result = transactionSender.handleCompletedTask(peer, task)
        assertTrue(result)
    }

    @Test
    fun `handleCompletedTask - owner is other - rejected`() {
        val otherOwner = Object()
        val task = mock<SendTransactionTask>()
        whenever(task.owner).thenReturn(otherOwner)

        val result = transactionSender.handleCompletedTask(peer, task)
        assertFalse(result)
    }

    @Test
    fun `handleCompletedTask - non-SendTransactionTask - rejected regardless of owner`() {
        val task = mock<PeerTask>()
        whenever(task.owner).thenReturn(null)

        val result = transactionSender.handleCompletedTask(peer, task)
        assertFalse(result)
    }

    @Test
    fun `onPeerReject tracked sent transaction checks sent storage only`() {
        val hash = ByteArray(32) { it.toByte() }
        whenever(storage.getSentTransaction(hash)).thenReturn(mock<SentTransaction>())

        transactionSender.onPeerReject(peer, RejectMessage("tx", 0x10.toByte(), "invalid", hash))

        verify(storage).getSentTransaction(hash)
        verify(storage, never()).getTransaction(hash)
    }

    @Test
    fun `onPeerReject untracked transaction checks wallet storage`() {
        val hash = ByteArray(32) { it.toByte() }
        whenever(storage.getSentTransaction(hash)).thenReturn(null)
        whenever(storage.getTransaction(hash)).thenReturn(null)

        transactionSender.onPeerReject(peer, RejectMessage("tx", 0x10.toByte(), "invalid", hash))

        verify(storage).getSentTransaction(hash)
        verify(storage).getTransaction(hash)
    }
}

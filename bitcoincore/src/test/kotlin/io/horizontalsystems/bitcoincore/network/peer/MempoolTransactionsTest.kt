package io.horizontalsystems.bitcoincore.network.peer

import com.nhaarman.mockitokotlin2.check
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.models.InventoryItem
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.bitcoincore.network.peer.task.RequestTransactionsTask
import io.horizontalsystems.bitcoincore.network.peer.task.SendTransactionTask
import io.horizontalsystems.bitcoincore.transactions.TransactionSender
import io.horizontalsystems.bitcoincore.transactions.TransactionSyncer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MempoolTransactionsTest {

    private lateinit var transactionSyncer: TransactionSyncer
    private lateinit var transactionSender: TransactionSender
    private lateinit var mempoolTransactions: MempoolTransactions
    private lateinit var peer: Peer

    @Before
    fun setup() {
        transactionSyncer = mock()
        transactionSender = mock()
        mempoolTransactions = MempoolTransactions(transactionSyncer, transactionSender)
        peer = mock { on { host } doReturn "1.2.3.4" }
    }

    // Task ownership tests

    @Test
    fun `handleCompletedTask - null owner - handled for RequestTransactionsTask`() {
        val task = RequestTransactionsTask(emptyList())
        task.owner = null

        val result = mempoolTransactions.handleCompletedTask(peer, task)
        assertTrue(result)
        verify(transactionSyncer).handleRelayed(task.transactions)
    }

    @Test
    fun `handleCompletedTask - owner is this - handled and transactions relayed`() {
        val task = RequestTransactionsTask(emptyList())
        task.owner = mempoolTransactions

        val result = mempoolTransactions.handleCompletedTask(peer, task)
        assertTrue(result)
        verify(transactionSyncer).handleRelayed(task.transactions)
        verify(transactionSender).transactionsRelayed(task.transactions)
    }

    @Test
    fun `handleCompletedTask - owner is other - rejected`() {
        val otherOwner = Object()
        val task = RequestTransactionsTask(emptyList())
        task.owner = otherOwner

        val result = mempoolTransactions.handleCompletedTask(peer, task)
        assertFalse(result)
        verify(transactionSyncer, never()).handleRelayed(task.transactions)
    }

    @Test
    fun `handleCompletedTask - unrelated task type - returns false`() {
        val task = mock<SendTransactionTask>()
        whenever(task.owner).thenReturn(null)

        val result = mempoolTransactions.handleCompletedTask(peer, task)
        assertFalse(result)
    }

    // handleInventoryItems

    @Test
    fun `handleInventoryItems sets task owner to this`() {
        val hash = ByteArray(32) { 1 }
        val items = listOf(InventoryItem(InventoryItem.MSG_TX, hash))
        whenever(transactionSyncer.shouldRequestTransaction(hash)).thenReturn(true)

        mempoolTransactions.handleInventoryItems(peer, items)

        verify(peer).addTask(check { task ->
            assertTrue(task is RequestTransactionsTask)
            assertTrue(task.owner === mempoolTransactions)
        })
    }

    @Test
    fun `handleInventoryItems does not create task for non-TX items`() {
        val items = listOf(InventoryItem(InventoryItem.MSG_BLOCK, ByteArray(32)))

        mempoolTransactions.handleInventoryItems(peer, items)

        verify(peer, never()).addTask(com.nhaarman.mockitokotlin2.any())
    }

    // Null sender

    @Test
    fun `handles task without crashing when sender is null`() {
        val mempoolNoSender = MempoolTransactions(transactionSyncer, null)
        val task = RequestTransactionsTask(emptyList())
        task.owner = null

        val result = mempoolNoSender.handleCompletedTask(peer, task)
        assertTrue(result)
        verify(transactionSyncer).handleRelayed(task.transactions)
    }
}

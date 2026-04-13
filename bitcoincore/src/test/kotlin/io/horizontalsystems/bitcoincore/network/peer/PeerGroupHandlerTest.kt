package io.horizontalsystems.bitcoincore.network.peer

import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IPeerAddressManager
import io.horizontalsystems.bitcoincore.models.InventoryItem
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.InvMessage
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import io.horizontalsystems.bitcoincore.network.messages.RejectMessage
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import org.junit.Before
import org.junit.Test

class PeerGroupHandlerTest {

    private lateinit var peerGroup: PeerGroup
    private lateinit var peer: Peer

    @Before
    fun setup() {
        peerGroup = PeerGroup(
            mock<IPeerAddressManager>(),
            mock { on { logTag } doReturn "TestNetwork" },
            PeerManager(),
            10,
            mock<NetworkMessageParser>(),
            mock<NetworkMessageSerializer>(),
            mock<IConnectionManager>(),
            0,
            false
        )
        peer = mock { on { host } doReturn "1.2.3.4" }
    }

    // Inventory handlers (forEach semantics)

    @Test
    fun `single inventory handler receives items`() {
        val handler = mock<IInventoryItemsHandler>()
        peerGroup.addInventoryItemsHandler(handler)

        val items = listOf(InventoryItem(InventoryItem.MSG_TX, ByteArray(32)))
        val message = InvMessage(items)

        peerGroup.onReceiveMessage(peer, message)

        verify(handler).handleInventoryItems(peer, message.inventory)
    }

    @Test
    fun `multiple inventory handlers all receive items`() {
        val handler1 = mock<IInventoryItemsHandler>()
        val handler2 = mock<IInventoryItemsHandler>()
        peerGroup.addInventoryItemsHandler(handler1)
        peerGroup.addInventoryItemsHandler(handler2)

        val items = listOf(InventoryItem(InventoryItem.MSG_TX, ByteArray(32)))
        val message = InvMessage(items)

        peerGroup.onReceiveMessage(peer, message)

        verify(handler1).handleInventoryItems(peer, message.inventory)
        verify(handler2).handleInventoryItems(peer, message.inventory)
    }

    @Test
    fun `no inventory handlers registered - no crash`() {
        val items = listOf(InventoryItem(InventoryItem.MSG_TX, ByteArray(32)))
        val message = InvMessage(items)

        // Should not throw
        peerGroup.onReceiveMessage(peer, message)
    }

    @Test
    fun `peer group listener receives reject messages`() {
        val message = RejectMessage("tx", 0x10.toByte(), "invalid")
        var receivedPeer: Peer? = null
        var receivedMessage: RejectMessage? = null
        val listener = object : PeerGroup.Listener {
            override fun onPeerReject(peer: Peer, rejectMessage: RejectMessage) {
                receivedPeer = peer
                receivedMessage = rejectMessage
            }
        }
        peerGroup.addPeerGroupListener(listener)

        peerGroup.onReceiveMessage(peer, message)

        org.junit.Assert.assertSame(peer, receivedPeer)
        org.junit.Assert.assertSame(message, receivedMessage)
    }

    // Task handlers (any/first-wins semantics)

    @Test
    fun `single task handler returns true - handled`() {
        val handler = mock<IPeerTaskHandler>()
        val task = mock<PeerTask>()
        whenever(handler.handleCompletedTask(peer, task)).thenReturn(true)
        peerGroup.addPeerTaskHandler(handler)

        peerGroup.onTaskComplete(peer, task)

        verify(handler).handleCompletedTask(peer, task)
    }

    @Test
    fun `first handler false second true - second is called`() {
        val handler1 = mock<IPeerTaskHandler>()
        val handler2 = mock<IPeerTaskHandler>()
        val task = mock<PeerTask>()
        whenever(handler1.handleCompletedTask(peer, task)).thenReturn(false)
        whenever(handler2.handleCompletedTask(peer, task)).thenReturn(true)
        peerGroup.addPeerTaskHandler(handler1)
        peerGroup.addPeerTaskHandler(handler2)

        peerGroup.onTaskComplete(peer, task)

        verify(handler1).handleCompletedTask(peer, task)
        verify(handler2).handleCompletedTask(peer, task)
    }

    @Test
    fun `first handler true - second NOT called (short-circuit)`() {
        val handler1 = mock<IPeerTaskHandler>()
        val handler2 = mock<IPeerTaskHandler>()
        val task = mock<PeerTask>()
        whenever(handler1.handleCompletedTask(peer, task)).thenReturn(true)
        peerGroup.addPeerTaskHandler(handler1)
        peerGroup.addPeerTaskHandler(handler2)

        peerGroup.onTaskComplete(peer, task)

        verify(handler1).handleCompletedTask(peer, task)
        verify(handler2, never()).handleCompletedTask(peer, task)
    }

    @Test
    fun `no task handlers registered - no crash`() {
        val task = mock<PeerTask>()

        // Should not throw
        peerGroup.onTaskComplete(peer, task)
    }
}

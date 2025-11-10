package io.horizontalsystems.cosantakit.tasks

import io.horizontalsystems.bitcoincore.models.InventoryItem
import io.horizontalsystems.bitcoincore.network.messages.GetDataMessage
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.cosantakit.messages.ISLockMessage

class RequestInstantSendLocksTask(inventoryItems: List<InventoryItem>) : PeerTask() {

    val inventoryItems = inventoryItems.toMutableList()
    var isLocks = mutableListOf<ISLockMessage>()

    override fun start() {
        requester?.send(GetDataMessage(inventoryItems))
    }

    override fun handleMessage(message: IMessage) = when (message) {
        is ISLockMessage -> handleISLockVote(message)
        else -> false
    }

    private fun handleISLockVote(isLockMessage: ISLockMessage): Boolean {
        val item = inventoryItems.firstOrNull { it.hash.contentEquals(isLockMessage.hash) } ?: return false

        inventoryItems.remove(item)
        isLocks.add(isLockMessage)

        if (inventoryItems.isEmpty()) {
            listener?.onTaskCompleted(this)
        }

        return true
    }

}

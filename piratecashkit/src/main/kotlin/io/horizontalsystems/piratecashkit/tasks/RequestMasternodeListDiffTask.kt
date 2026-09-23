package io.horizontalsystems.piratecashkit.tasks

import co.touchlab.kermit.Logger
import io.horizontalsystems.bitcoincore.network.messages.IMessage
import io.horizontalsystems.bitcoincore.network.peer.task.PeerTask
import io.horizontalsystems.piratecashkit.messages.GetMasternodeListDiffMessage
import io.horizontalsystems.piratecashkit.messages.MasternodeListDiffMessage
import java.util.concurrent.TimeUnit

class RequestMasternodeListDiffTask(private val baseBlockHash: ByteArray, private val blockHash: ByteArray, private val logTag: String) : PeerTask() {
    private val log = Logger.withTag(logTag)

    var masternodeListDiffMessage: MasternodeListDiffMessage? = null

    init {
        allowedIdleTime = TimeUnit.SECONDS.toMillis(5)
    }

    override fun handleTimeout() {
        log.d { "RequestMasternodeListDiffTask: timeout" }
        listener?.onTaskCompleted(this)
    }


    override fun start() {
        requester?.send(GetMasternodeListDiffMessage(baseBlockHash, blockHash))
        resetTimer()
    }

    override fun handleMessage(message: IMessage): Boolean {
        if (message is MasternodeListDiffMessage
                && message.baseBlockHash.contentEquals(baseBlockHash)
                && message.blockHash.contentEquals(blockHash)) {

            masternodeListDiffMessage = message

            listener?.onTaskCompleted(this)

            return true
        }

        return false
    }
}

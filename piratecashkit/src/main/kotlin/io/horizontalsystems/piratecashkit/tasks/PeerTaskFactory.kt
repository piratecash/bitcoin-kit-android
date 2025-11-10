package io.horizontalsystems.piratecashkit.tasks

class PeerTaskFactory {

    fun createRequestMasternodeListDiffTask(
        baseBlockHash: ByteArray,
        blockHash: ByteArray,
        logTag: String
    ): RequestMasternodeListDiffTask {
        return RequestMasternodeListDiffTask(baseBlockHash, blockHash, logTag)
    }

}

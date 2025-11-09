package io.horizontalsystems.dashkit.managers

import io.horizontalsystems.dashkit.models.Masternode

class MasternodeSortedList {
    private val masternodeList = mutableListOf<Masternode>()
    private val lock = Any()

    val masternodes: List<Masternode>
        get() = synchronized(lock) {
            masternodeList.sorted()
        }

    fun add(masternodes: List<Masternode>) = synchronized(lock) {
        masternodeList.removeAll(masternodes)
        masternodeList.addAll(masternodes)
    }

    fun remove(proRegTxHashes: List<ByteArray>) = synchronized(lock) {
        proRegTxHashes.forEach { hash ->
            val index = masternodeList.indexOfFirst { masternode ->
                masternode.proRegTxHash.contentEquals(hash)
            }

            if (index != -1) {
                masternodeList.removeAt(index)
            }
        }
    }

    fun removeAll() = synchronized(lock) {
        masternodeList.clear()
    }

}

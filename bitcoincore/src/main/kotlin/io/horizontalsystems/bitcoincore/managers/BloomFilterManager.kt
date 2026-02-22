package io.horizontalsystems.bitcoincore.managers

import io.horizontalsystems.bitcoincore.crypto.BloomFilter

class BloomFilterManager {

    object BloomFilterExpired : Exception()

    interface Listener {
        fun onFilterUpdated(bloomFilter: BloomFilter)
    }

    var listener: Listener? = null
    var bloomFilter: BloomFilter? = null

    private val bloomFilterProviders = java.util.concurrent.CopyOnWriteArrayList<IBloomFilterProvider>()

    init {
        regenerateBloomFilter()
    }

    fun regenerateBloomFilter() {
        val elements = mutableListOf<ByteArray>()

        bloomFilterProviders.forEach {
            elements.addAll(it.getBloomFilterElements())
        }

        if (elements.isNotEmpty()) {
            BloomFilter(elements).let {
                bloomFilter = it
                listener?.onFilterUpdated(it)
            }
        }
    }

    fun addBloomFilterProvider(provider: IBloomFilterProvider) {
        provider.bloomFilterManager = this
        bloomFilterProviders.add(provider)
    }

    fun removeBloomFilterProvider(provider: IBloomFilterProvider) {
        bloomFilterProviders.remove(provider)
        provider.bloomFilterManager = null
    }
}

interface IBloomFilterProvider {
    var bloomFilterManager: BloomFilterManager?

    fun getBloomFilterElements(): List<ByteArray>
}

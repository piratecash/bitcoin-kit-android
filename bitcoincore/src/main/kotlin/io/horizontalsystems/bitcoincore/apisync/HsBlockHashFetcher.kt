package io.horizontalsystems.bitcoincore.apisync

import io.horizontalsystems.bitcoincore.apisync.blockchair.IBlockHashFetcher
import io.horizontalsystems.bitcoincore.managers.ApiManager
import io.horizontalsystems.bitcoincore.network.NetworkErrorListenerHolder

class HsBlockHashFetcher(
    url: String,
    networkErrorListener: NetworkErrorListenerHolder? = null
) : IBlockHashFetcher {
    private val apiManager = ApiManager(url, networkErrorListener)

    override fun fetch(heights: List<Int>): Map<Int, String> {
        val joinedHeights = heights.sorted().joinToString(",") { it.toString() }
        val blocks = apiManager.doOkHttpGet("hashes?numbers=$joinedHeights").asArray()

        return blocks.associate { blockJson ->
            val block = blockJson.asObject()
            Pair(
                block["number"].asInt(),
                block["hash"].asString()
            )
        }
    }
}

package io.horizontalsystems.bitcoincore.apisync.blockchair


interface IBlockHashFetcher {
    fun fetch(heights: List<Int>): Map<Int, String>
}

class BlockchairBlockHashFetcher(
    private val blockchairApi: Api
) : IBlockHashFetcher {
    override fun fetch(heights: List<Int>): Map<Int, String> {
        return blockchairApi.blockHashes(heights)
    }
}

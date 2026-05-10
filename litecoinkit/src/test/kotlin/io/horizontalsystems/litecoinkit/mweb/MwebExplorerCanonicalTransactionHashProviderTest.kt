package io.horizontalsystems.litecoinkit.mweb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MwebExplorerCanonicalTransactionHashProviderTest {
    @Test
    fun parseTransactionHash_blockPage_returnsMwebTransactionHash() {
        val hash = "48963c869885dd5b7c7744e77b37aaf4dcb8bc11a8f5437f6f8cd87be7948fa8"
        val html = """
            <div>Litecoin Block Hash: 0dea83ad35f54d3324acdfc8e9bbb89acc80e065ba893279b7d478823f5836a4</div>
            <div>MWEB Transaction Hash: <a href="/tx/$hash">$hash</a></div>
        """.trimIndent()

        assertEquals(hash, MwebExplorerCanonicalTransactionHashProvider.parseTransactionHash(html))
    }

    @Test
    fun parseTransactionHash_missingLabel_returnsNull() {
        val html = "<div>Litecoin Block Hash: 0dea83ad35f54d3324acdfc8e9bbb89acc80e065ba893279b7d478823f5836a4</div>"

        assertNull(MwebExplorerCanonicalTransactionHashProvider.parseTransactionHash(html))
    }

    @Test
    fun parseTransactionHash_hashBeforeLabel_returnsMwebTransactionHash() {
        val blockHash = "0dea83ad35f54d3324acdfc8e9bbb89acc80e065ba893279b7d478823f5836a4"
        val mwebHash = "48963C869885DD5B7C7744E77B37AAF4DCB8BC11A8F5437F6F8CD87BE7948FA8"
        val html = """
            <div>Litecoin Block Hash: $blockHash</div>
            <div>MWEB Transaction Hash: $mwebHash</div>
        """.trimIndent()

        assertEquals(mwebHash.lowercase(), MwebExplorerCanonicalTransactionHashProvider.parseTransactionHash(html))
    }

    @Test
    fun parseTransactionHash_labelWithoutHash_returnsNull() {
        val html = "<div>MWEB Transaction Hash: pending</div>"

        assertNull(MwebExplorerCanonicalTransactionHashProvider.parseTransactionHash(html))
    }

    @Test
    fun transactionHash_missingHash_usesNegativeCache() = runBlocking {
        var now = 1_000L
        var fetchCount = 0
        val provider = MwebExplorerCanonicalTransactionHashProvider(
            blockPageProvider = {
                fetchCount += 1
                "<div>MWEB Transaction Hash: pending</div>"
            },
            currentTimeMillisProvider = { now },
        )

        assertNull(provider.transactionHash(100))
        assertNull(provider.transactionHash(100))
        assertEquals(1, fetchCount)

        now += 301_000

        assertNull(provider.transactionHash(100))
        assertEquals(2, fetchCount)
    }
}

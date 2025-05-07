package io.horizontalsystems.tools

import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.horizontalsystems.bitcoincore.utils.HashUtils
import java.util.logging.Level
import java.util.logging.Logger

// Go to
// Edit Configurations... -> ToolsKt -> VM Options
// And paste the following
// -classpath $Classpath$:bitcoincashkit/src/main/resources:bitcoinkit/src/main/resources:dashkit/src/main/resources:ecashkit/src/main/resources:litecoinkit/src/main/resources:dogecoinkit/src/main/resources:cosantakit/src/main/resources:piratecashkit/src/main/resources
fun main() {
    Logger.getLogger("").level = Level.SEVERE
//    buildCustomCheckpoint()
    syncCheckpoints()
}

private fun syncCheckpoints() {
    BuildCheckpoints().sync()
    Thread.sleep(5000)
}

private fun buildCustomCheckpoint() {
    val checkpointBlock = Block(
        header = BlockHeader(
            version = 939524096,
            previousBlockHeaderHash = HashUtils.toBytesAsLE("c681477b3ffcb4df66b3a555abf93dfc6d7711290d4ebd6d6c95b613f362f908"),
            merkleRoot = HashUtils.toBytesAsLE("b7fd67de34e8673c9ec631509eddd67c7c356bdd12465921e909ff1ca66072d8"),
            timestamp = 1746083738,
            bits = 0x1a051435, // 0x1b32c619 соответствует полю "bits": "1b32c619"
            nonce = 163332392,
            hash = HashUtils.toBytesAsLE("0e8c5c847d7fe7ac75b7c42e6a0d6bbe9720abda158f560d131367d094eff76e")
        ),
        height = 1591125
    )

    BuildCheckpoints().build(checkpointBlock)
}

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
            previousBlockHeaderHash = HashUtils.toBytesAsLE("0e8c5c847d7fe7ac75b7c42e6a0d6bbe9720abda158f560d131367d094eff76e"),
            merkleRoot = HashUtils.toBytesAsLE("7070df121fb21096c7b799d4dff657319fc482834b7c0679473aedbdc0038e3a"),
            timestamp = 1746084122,
            bits = 0x1a06d57b,
            nonce = 3898578650,
            hash = HashUtils.toBytesAsLE("7ff1db771f0807ec1b72e36f4b278f8ee2fa8640b274f9b6e77de72761bd0c41")
        ),
        height = 1591125
    )

    BuildCheckpoints().build(checkpointBlock)
}

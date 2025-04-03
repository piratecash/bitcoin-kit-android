package io.horizontalsystems.tools

import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.storage.BlockHeader
import io.horizontalsystems.bitcoincore.utils.HashUtils
import java.util.logging.Level
import java.util.logging.Logger

// Go to
// Edit Configurations... -> ToolsKt -> VM Options
// And paste the following
// -classpath $Classpath$:bitcoincashkit/src/main/resources:bitcoinkit/src/main/resources:dashkit/src/main/resources:ecashkit/src/main/resources:litecoinkit/src/main/resources:dogecoinkit/src/main/resources:cosantakit/src/main/resources
fun main() {
    Logger.getLogger("").level = Level.SEVERE
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
            previousBlockHeaderHash = HashUtils.toBytesAsLE("000192e475f2f325cde276f22cf01414cd76416e1ac00937de7f82d00303b4de"),
            merkleRoot = HashUtils.toBytesAsLE("949055bf239b623f4e99ffc01730863440c91700c62dcac30e2e31c3e60afdef"),
            timestamp = 1742511558,
            bits = 0x1b32c619, // 0x1b32c619 соответствует полю "bits": "1b32c619"
            nonce = 2968642242,
            hash = HashUtils.toBytesAsLE("0000db0b2355298eac8f1d508ba044f157d47fb59a7cfe3c4dc8a026eb825fbb")
        ),
        height = 735306
    )

    BuildCheckpoints().build(checkpointBlock)
}

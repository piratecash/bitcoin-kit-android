package io.horizontalsystems.cosantakit

import io.horizontalsystems.bitcoincore.apisync.blockchair.LastBlockProvider
import io.horizontalsystems.bitcoincore.apisync.model.BlockHeaderItem

class CosantaLastBlockProvider(
    private val cosantaApi: CosantaApi
): LastBlockProvider {
    override fun lastBlockHeader(): BlockHeaderItem {
        return cosantaApi.lastBlockHeader()
    }
}

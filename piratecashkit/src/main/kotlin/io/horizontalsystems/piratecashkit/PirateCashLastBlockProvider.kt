package io.horizontalsystems.piratecashkit

import io.horizontalsystems.bitcoincore.apisync.blockchair.LastBlockProvider
import io.horizontalsystems.bitcoincore.apisync.model.BlockHeaderItem

class PirateCashLastBlockProvider(
    private val pirateCashApi: PirateCashApi
): LastBlockProvider {
    override fun lastBlockHeader(): BlockHeaderItem {
        return pirateCashApi.lastBlockHeader()
    }
}

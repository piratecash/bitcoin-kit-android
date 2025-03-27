package io.horizontalsystems.bitcoincore.apisync.blockchair

import io.horizontalsystems.bitcoincore.apisync.model.BlockHeaderItem

interface LastBlockProvider {
    fun lastBlockHeader(): BlockHeaderItem
}

package io.horizontalsystems.bitcoincore.apisync.blockchair

import io.horizontalsystems.bitcoincore.apisync.model.BlockHeaderItem
import io.horizontalsystems.bitcoincore.apisync.model.TransactionItem

interface Api {

    fun transactions(addresses: List<String>, stopHeight: Int?): List<TransactionItem>

    fun blockHashes(heights: List<Int>): Map<Int, String>

    fun lastBlockHeader(): BlockHeaderItem

    fun broadcastTransaction(rawTransactionHex: String)
}

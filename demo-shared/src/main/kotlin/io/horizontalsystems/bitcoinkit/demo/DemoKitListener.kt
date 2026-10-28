package io.horizontalsystems.bitcoinkit.demo

import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.BlockInfo
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.litecoinkit.mweb.MwebBalance
import io.horizontalsystems.litecoinkit.mweb.MwebSyncState
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo

/**
 * Single listener shape for all eight kits. Every callback carries its originating [DemoKit] so a
 * cached, paused kit can be told apart from the active one.
 */
interface DemoKitListener {
    fun onTransactionsUpdate(kit: DemoKit, inserted: List<TransactionInfo>, updated: List<TransactionInfo>)
    fun onTransactionsDelete(kit: DemoKit, hashes: List<String>)
    fun onBalanceUpdate(kit: DemoKit, balance: BalanceInfo)
    fun onLastBlockInfoUpdate(kit: DemoKit, blockInfo: BlockInfo)
    fun onKitStateUpdate(kit: DemoKit, state: BitcoinCore.KitState)
    fun onMwebBalanceUpdate(kit: DemoKit, balance: MwebBalance)
    fun onMwebSyncStateUpdate(kit: DemoKit, state: MwebSyncState)
    fun onMwebUtxosUpdate(kit: DemoKit, utxos: List<MwebUtxo>)
}

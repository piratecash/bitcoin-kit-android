package io.horizontalsystems.bitcoinkit.demo

import cash.p.dogecoinkit.DogecoinKit
import io.horizontalsystems.bitcoincash.BitcoinCashKit
import io.horizontalsystems.bitcoincore.AbstractKit
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.core.IPluginData
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.BlockInfo
import io.horizontalsystems.bitcoincore.models.TransactionDataSortType
import io.horizontalsystems.bitcoincore.models.TransactionFilterType
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoinkit.BitcoinKit
import io.horizontalsystems.cosantakit.CosantaKit
import io.horizontalsystems.dashkit.DashKit
import io.horizontalsystems.ecash.ECashKit
import io.horizontalsystems.litecoinkit.LitecoinKit
import io.horizontalsystems.litecoinkit.LitecoinMwebState
import io.horizontalsystems.litecoinkit.LitecoinReceiveAddressType
import io.horizontalsystems.litecoinkit.LitecoinSendInfo
import io.horizontalsystems.litecoinkit.LitecoinSendResult
import io.horizontalsystems.litecoinkit.LitecoinSendSource
import io.horizontalsystems.litecoinkit.mweb.MwebBalance
import io.horizontalsystems.litecoinkit.mweb.MwebSyncState
import io.horizontalsystems.litecoinkit.mweb.MwebUtxo
import io.horizontalsystems.piratecashkit.PirateCashKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal fun FullTransaction.toSendOutcome() = SendOutcome(header.serializedTxInfo, mweb = false)

internal fun LitecoinSendResult.toSendOutcome() = when (this) {
    is LitecoinSendResult.Public -> transaction.toSendOutcome()
    is LitecoinSendResult.Mweb -> SendOutcome(
        id = transaction.canonicalTransactionHash ?: transaction.outputIds.joinToString(),
        mweb = true,
    )
}

/**
 * Makes the eight kits look like one to the rest of the demo: a single listener shape, and one
 * signature for the three operations whose per-kit variants differ.
 *
 * Every kit call here blocks; callers must confine them to a single background dispatcher.
 */
class DemoKit(
    val type: KitType,
    internal val kit: AbstractKit,
    private val dataDir: String,
    private val mwebDataDir: String,
    private val walletId: String,
    private val scope: CoroutineScope,
    private val listener: DemoKitListener,
) {
    val capabilities = type.capabilities

    init {
        when (kit) {
            is BitcoinKit -> kit.listener = CoreListenerAdapter()
            is BitcoinCashKit -> kit.listener = CoreListenerAdapter()
            is ECashKit -> kit.listener = CoreListenerAdapter()
            is DogecoinKit -> kit.listener = CoreListenerAdapter()
            is LitecoinKit -> kit.listener = LitecoinListenerAdapter()
            is DashKit -> kit.listener = DashFamilyListenerAdapter()
            is CosantaKit -> kit.listener = DashFamilyListenerAdapter()
            is PirateCashKit -> kit.listener = DashFamilyListenerAdapter()
        }
    }

    val networkName: String get() = kit.networkName
    val balance: BalanceInfo get() = kit.balance
    val lastBlockInfo: BlockInfo? get() = kit.lastBlockInfo
    val syncState: BitcoinCore.KitState get() = kit.syncState

    /** MWEB runtime state, or null unless this is Litecoin with MWEB enabled. */
    val mwebState: LitecoinMwebState? get() = (kit as? LitecoinKit)?.mwebState

    fun start() = kit.start()

    fun stop() = kit.stop()

    fun refresh() = kit.refresh()

    fun pauseNetwork() = kit.pauseNetwork()

    fun dispose() = kit.dispose()

    fun showDebugInfo() = kit.showDebugInfo()

    fun statusInfo(): Map<String, Any> = kit.statusInfo()

    fun transactions(type: TransactionFilterType?): List<TransactionInfo> =
        kit.transactions(type = type).blockingGet()

    fun receiveAddress(mweb: Boolean): String = when {
        kit is LitecoinKit && mweb -> kit.receiveAddress(LitecoinReceiveAddressType.Mweb)
        kit is LitecoinKit -> kit.receiveAddress(LitecoinReceiveAddressType.Public)
        else -> kit.receiveAddress()
    }

    fun fee(
        value: Long,
        address: String?,
        source: LitecoinSendSource,
        feeRate: Int,
        pluginData: Map<Byte, IPluginData>,
    ): Long = if (kit is LitecoinKit && address != null) {
        when (val info = kit.sendInfo(value, address, null, source, feeRate, null, pluginData, false, UtxoFilters())) {
            is LitecoinSendInfo.Public -> info.sendInfo.fee
            is LitecoinSendInfo.Mweb -> info.sendInfo.totalFee
        }
    } else {
        kit.sendInfo(
            value = value,
            address = address,
            memo = null,
            feeRate = feeRate,
            unspentOutputs = null,
            pluginData = pluginData,
            changeToFirstInput = false,
            filters = UtxoFilters(),
        ).fee
    }

    suspend fun send(
        address: String,
        value: Long,
        source: LitecoinSendSource,
        feeRate: Int,
        pluginData: Map<Byte, IPluginData>,
    ): SendOutcome = if (kit is LitecoinKit) {
        kit.send(
            address = address,
            memo = null,
            value = value,
            source = source,
            feeRate = feeRate,
            sortType = TransactionDataSortType.Shuffle,
            pluginData = pluginData,
            rbfEnabled = true,
            changeToFirstInput = false,
            filters = UtxoFilters(),
        ).toSendOutcome()
    } else {
        kit.send(
            address = address,
            memo = null,
            value = value,
            feeRate = feeRate,
            sortType = TransactionDataSortType.Shuffle,
            pluginData = pluginData,
            rbfEnabled = true,
            changeToFirstInput = false,
            filters = UtxoFilters(),
        ).toSendOutcome()
    }

    fun maximumSpendableValue(
        address: String?,
        feeRate: Int,
        pluginData: Map<Byte, IPluginData>,
    ): Long = kit.maximumSpendableValue(
        address = address,
        memo = null,
        feeRate = feeRate,
        unspentOutputInfos = null,
        pluginData = pluginData,
        changeToFirstInput = false,
        filters = UtxoFilters(),
    )

    fun rawTransaction(transactionHash: String): String? = kit.getRawTransaction(transactionHash)

    /** Deletes this kit's databases and returns whatever survived the deletion. */
    fun clear(): List<String> = KitFactory.clear(type, dataDir, mwebDataDir, walletId)

    /**
     * The kits raise callbacks on whichever thread produced them — `BitcoinCore`'s default
     * listener executor runs them inline — so every forward hops to main before touching UI state.
     */
    private fun deliver(block: DemoKitListener.(DemoKit) -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { listener.block(this@DemoKit) }
    }

    // All four are empty extensions of BitcoinCore.Listener, so one class satisfies them all;
    // Kotlin interfaces are nominal, so BitcoinCore.Listener alone would not be assignable.
    private inner class CoreListenerAdapter :
        BitcoinKit.Listener,
        BitcoinCashKit.Listener,
        ECashKit.Listener,
        DogecoinKit.Listener {

        override fun onTransactionsUpdate(inserted: List<TransactionInfo>, updated: List<TransactionInfo>) =
            deliver { onTransactionsUpdate(it, inserted, updated) }

        override fun onTransactionsDelete(hashes: List<String>) =
            deliver { onTransactionsDelete(it, hashes) }

        override fun onBalanceUpdate(balance: BalanceInfo) =
            deliver { onBalanceUpdate(it, balance) }

        override fun onLastBlockInfoUpdate(blockInfo: BlockInfo) =
            deliver { onLastBlockInfoUpdate(it, blockInfo) }

        override fun onKitStateUpdate(state: BitcoinCore.KitState) =
            deliver { onKitStateUpdate(it, state) }
    }

    private inner class LitecoinListenerAdapter : LitecoinKit.Listener {

        override fun onTransactionsUpdate(inserted: List<TransactionInfo>, updated: List<TransactionInfo>) =
            deliver { onTransactionsUpdate(it, inserted, updated) }

        override fun onTransactionsDelete(hashes: List<String>) =
            deliver { onTransactionsDelete(it, hashes) }

        override fun onBalanceUpdate(balance: BalanceInfo) =
            deliver { onBalanceUpdate(it, balance) }

        override fun onLastBlockInfoUpdate(blockInfo: BlockInfo) =
            deliver { onLastBlockInfoUpdate(it, blockInfo) }

        override fun onKitStateUpdate(state: BitcoinCore.KitState) =
            deliver { onKitStateUpdate(it, state) }

        override fun onMwebBalanceUpdate(balance: MwebBalance) =
            deliver { onMwebBalanceUpdate(it, balance) }

        override fun onMwebSyncStateUpdate(state: MwebSyncState) =
            deliver { onMwebSyncStateUpdate(it, state) }

        override fun onMwebUtxosUpdate(utxos: List<MwebUtxo>) =
            deliver { onMwebUtxosUpdate(it, utxos) }
    }

    // The three Dash-family listeners are unrelated interfaces with identical members and no
    // defaults, so a single set of overrides satisfies all three.
    private inner class DashFamilyListenerAdapter :
        DashKit.Listener,
        CosantaKit.Listener,
        PirateCashKit.Listener {

        override fun onTransactionsUpdate(inserted: List<TransactionInfo>, updated: List<TransactionInfo>) =
            deliver { onTransactionsUpdate(it, inserted, updated) }

        override fun onTransactionsDelete(hashes: List<String>) =
            deliver { onTransactionsDelete(it, hashes) }

        override fun onBalanceUpdate(balance: BalanceInfo) =
            deliver { onBalanceUpdate(it, balance) }

        override fun onLastBlockInfoUpdate(blockInfo: BlockInfo) =
            deliver { onLastBlockInfoUpdate(it, blockInfo) }

        override fun onKitStateUpdate(state: BitcoinCore.KitState) =
            deliver { onKitStateUpdate(it, state) }
    }
}

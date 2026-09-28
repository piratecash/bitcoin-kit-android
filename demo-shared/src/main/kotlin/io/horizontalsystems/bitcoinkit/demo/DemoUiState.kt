package io.horizontalsystems.bitcoinkit.demo

import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.BlockInfo
import io.horizontalsystems.bitcoincore.models.TransactionFilterType
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hodler.LockTimeInterval
import io.horizontalsystems.litecoinkit.LitecoinReceiveAddressType
import io.horizontalsystems.litecoinkit.LitecoinSendSource

/** Identifier of a broadcast transaction, plus which Litecoin path produced it. */
data class SendOutcome(val id: String, val mweb: Boolean) {
    val message: String get() = if (mweb) "MWEB transaction sent $id" else "Transaction sent $id"
}

data class DemoUiState(
    val kitType: KitType = KitType.Bitcoin,
    val purpose: Purpose = Purpose.BIP44,
    val capabilities: KitCapabilities = KitCapabilities(hodler = false, mweb = false, purpose = false),
    val networkName: String = "",
    val running: Boolean = false,
    val balance: BalanceInfo? = null,
    val lastBlock: BlockInfo? = null,
    val syncState: BitcoinCore.KitState? = null,
    val mwebStatus: String = "",
    val masternodeCount: Int? = null,
    val transactions: List<TransactionInfo> = emptyList(),
    val filter: TransactionFilterType? = null,
    val receiveAddress: String = "",
    val receiveAddressType: LitecoinReceiveAddressType = LitecoinReceiveAddressType.Public,
    val sendSource: LitecoinSendSource = LitecoinSendSource.Auto,
    val address: String = "",
    val amount: Long? = null,
    val fee: Long? = null,
    val feePriority: FeePriority = FeePriority.Medium,
    val lockTimeInterval: LockTimeInterval? = null,
    val rawTransaction: String? = null,
    val statusInfo: Map<String, Any>? = null,
    val kitReady: Boolean = false,
    val sendInFlight: Boolean = false,
    val transitionInFlight: Boolean = false,
    val sendResult: String? = null,
    val error: String? = null,
) {
    /**
     * Gates every control that reaches the kit. The kit selector is the one exception: re-selecting
     * is the retry after a construction failure, so it stays live while no transition is running.
     */
    val kitControlsEnabled: Boolean get() = kitReady && !transitionInFlight

    val kitSelectorEnabled: Boolean get() = !transitionInFlight && !sendInFlight
}

/**
 * Drops everything that belongs to a particular kit — the displayed numbers, the values computed
 * against it and the controls only some kits offer — so no field can outlive the kit it describes.
 */
internal fun DemoUiState.withoutKitData(): DemoUiState = copy(
    networkName = "",
    balance = null,
    lastBlock = null,
    syncState = null,
    mwebStatus = "",
    masternodeCount = null,
    transactions = emptyList(),
    receiveAddress = "",
    receiveAddressType = LitecoinReceiveAddressType.Public,
    address = "",
    amount = null,
    sendSource = LitecoinSendSource.Auto,
    lockTimeInterval = null,
    fee = null,
    rawTransaction = null,
    statusInfo = null,
    sendResult = null,
    error = null,
)

/** Identity, capabilities and the kit-derived fields are published in one write. */
internal fun DemoUiState.committedTo(type: KitType, purpose: Purpose): DemoUiState = withoutKitData().copy(
    kitType = type,
    purpose = purpose,
    capabilities = type.capabilities,
    kitReady = true,
)

internal fun DemoUiState.released(): DemoUiState = withoutKitData().copy(kitReady = false)

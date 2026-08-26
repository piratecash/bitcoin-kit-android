package io.horizontalsystems.bitcoinkit.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.BlockInfo

private val previewState = DemoUiState(
    kitType = KitType.Litecoin,
    capabilities = KitType.Litecoin.capabilities,
    networkName = "Litecoin MainNet",
    running = true,
    balance = BalanceInfo(spendable = 123_456_789, unspendableTimeLocked = 1_000, unspendableNotRelayed = 0),
    lastBlock = BlockInfo(headerHash = "abcdef", height = 2_300_000, timestamp = 1_760_000_000),
    syncState = BitcoinCore.KitState.Syncing(0.42),
    mwebStatus = "MWEB 2300000/2300000, balance 500/0",
    receiveAddress = "ltc1qexampleaddress",
    address = "ltc1qdestinationaddress",
    amount = 100_000,
    fee = 7_000,
    kitReady = true,
)

@Preview
@Composable
private fun BalanceScreenPreview() {
    MaterialTheme {
        BalanceScreen(
            uiState = previewState,
            onStart = {},
            onStop = {},
            onRefresh = {},
            onClear = {},
            onDebug = {},
            onStatus = {},
            onPurposeChange = {},
        )
    }
}

@Preview
@Composable
private fun SendReceiveScreenPreview() {
    MaterialTheme {
        SendReceiveScreen(
            uiState = previewState,
            onReceiveClick = {},
            onReceiveAddressTypeChange = {},
            onAmountChange = {},
            onAddressChange = {},
            onFeePriorityChange = {},
            onSendSourceChange = {},
            onLockTimeIntervalChange = {},
            onMaxClick = {},
            onSendClick = {},
        )
    }
}

@Preview
@Composable
private fun TransactionsScreenPreview() {
    MaterialTheme {
        TransactionsScreen(
            uiState = previewState,
            onFilterChange = {},
            onRawTransactionClick = {},
        )
    }
}

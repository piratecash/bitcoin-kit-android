package io.horizontalsystems.bitcoinkit.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BalanceScreen(
    uiState: DemoUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onDebug: () -> Unit,
    onStatus: () -> Unit,
    onPurposeChange: (Purpose) -> Unit,
) {
    val enabled = uiState.kitControlsEnabled

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        InfoRow("Network", uiState.networkName)
        if (uiState.capabilities.purpose) {
            DropdownRow(
                label = "Purpose",
                options = Purpose.entries,
                selected = uiState.purpose,
                enabled = uiState.kitSelectorEnabled,
                optionLabel = { it.name },
                onSelect = onPurposeChange,
            )
        }
        InfoRow("Balance", uiState.balance?.spendable?.formatSatoshi().orEmpty())
        InfoRow(
            label = "Unspendable",
            value = uiState.balance
                ?.let { (it.unspendableTimeLocked + it.unspendableNotRelayed).formatSatoshi() }
                .orEmpty(),
        )
        InfoRow("Last block", uiState.lastBlock?.height?.toString().orEmpty())
        InfoRow("Last block date", uiState.lastBlock?.timestamp?.let(::formatBlockDate).orEmpty())
        InfoRow("State", uiState.syncState?.let(::describeSyncState).orEmpty())
        InfoRow("Masternodes", uiState.masternodeCount?.toString().orEmpty())
        if (uiState.capabilities.mweb) {
            InfoRow("MWEB", uiState.mwebStatus)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = enabled && !uiState.running) { Text("Start") }
            Button(onClick = onStop, enabled = enabled && uiState.running) { Text("Stop") }
            Button(onClick = onRefresh, enabled = enabled) { Text("Refresh") }
            // Clear wipes the kit's storage, so it also waits out an in-flight send.
            Button(onClick = onClear, enabled = enabled && !uiState.sendInFlight) { Text("Clear") }
            Button(onClick = onDebug, enabled = enabled) { Text("Debug") }
            Button(onClick = onStatus, enabled = enabled) { Text("Status") }
        }
    }
}

private fun describeSyncState(state: BitcoinCore.KitState) = when (state) {
    is BitcoinCore.KitState.Synced -> "synced"
    is BitcoinCore.KitState.ApiSyncing -> "api syncing ${state.transactions} txs"
    is BitcoinCore.KitState.Syncing -> "syncing ${"%.3f".format(state.progress)}"
    is BitcoinCore.KitState.NotSynced -> "not synced ${state.exception.javaClass.simpleName}"
}

private fun formatBlockDate(timestamp: Long) =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp * 1000))

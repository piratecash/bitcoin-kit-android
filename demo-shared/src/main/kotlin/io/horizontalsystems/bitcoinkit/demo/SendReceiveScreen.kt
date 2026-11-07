package io.horizontalsystems.bitcoinkit.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.horizontalsystems.hodler.LockTimeInterval
import io.horizontalsystems.litecoinkit.LitecoinReceiveAddressType
import io.horizontalsystems.litecoinkit.LitecoinSendSource

@Composable
fun SendReceiveScreen(
    uiState: DemoUiState,
    onReceiveClick: () -> Unit,
    onReceiveAddressTypeChange: (LitecoinReceiveAddressType) -> Unit,
    onAmountChange: (Long?) -> Unit,
    onAddressChange: (String) -> Unit,
    onFeePriorityChange: (FeePriority) -> Unit,
    onSendSourceChange: (LitecoinSendSource) -> Unit,
    onLockTimeIntervalChange: (LockTimeInterval?) -> Unit,
    onMaxClick: () -> Unit,
    onSendClick: () -> Unit,
) {
    val enabled = uiState.kitControlsEnabled

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onReceiveClick, enabled = enabled) { Text("Receive") }
            Text(
                text = uiState.receiveAddress,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (uiState.capabilities.mweb) {
            OptionRow(
                label = "Type",
                options = LitecoinReceiveAddressType.entries,
                selected = uiState.receiveAddressType,
                enabled = enabled,
                optionLabel = { it.name },
                onSelect = onReceiveAddressTypeChange,
            )
            Text(uiState.mwebStatus, style = MaterialTheme.typography.bodySmall)
        }

        OutlinedTextField(
            value = uiState.amount?.toString().orEmpty(),
            onValueChange = { onAmountChange(it.toLongOrNull()) },
            label = { Text("Amount, satoshi") },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = uiState.address,
            onValueChange = onAddressChange,
            label = { Text("Address") },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        InfoRow("Fee", uiState.fee?.toString().orEmpty())

        OptionRow(
            label = "Fee rate",
            options = FeePriority.entries,
            selected = uiState.feePriority,
            enabled = enabled,
            optionLabel = { it.name },
            onSelect = onFeePriorityChange,
        )

        if (uiState.capabilities.mweb) {
            OptionRow(
                label = "Source",
                options = LitecoinSendSource.entries,
                selected = uiState.sendSource,
                enabled = enabled,
                optionLabel = { it.name },
                onSelect = onSendSourceChange,
            )
        }

        if (uiState.capabilities.hodler) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Lock time", style = MaterialTheme.typography.bodyMedium)
                DropdownSelector(
                    options = listOf(null) + LockTimeInterval.entries,
                    selected = uiState.lockTimeInterval,
                    enabled = enabled,
                    optionLabel = { it?.name ?: "OFF" },
                    onSelect = onLockTimeIntervalChange,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onMaxClick, enabled = enabled) { Text("Max") }
            Button(onClick = onSendClick, enabled = enabled && !uiState.sendInFlight) { Text("Send") }
        }

        uiState.sendResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

package io.horizontalsystems.bitcoinkit.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private enum class DemoDestination(val title: String, val glyph: String) {
    Balance("Balance", "◎"),
    SendReceive("Send", "⇅"),
    Transactions("Transactions", "≡"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoApp(controller: DemoController) {
    val uiState = controller.uiState
    var destination by remember { mutableStateOf(DemoDestination.Balance) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        val error = uiState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        controller.dismissError()
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Bitcoin Kit demo") },
                    actions = {
                        DropdownSelector(
                            options = KitType.entries,
                            selected = uiState.kitType,
                            enabled = uiState.kitSelectorEnabled,
                            optionLabel = { it.displayName },
                            onSelect = controller::selectKit,
                        )
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    DemoDestination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == destination,
                            onClick = { destination = item },
                            icon = { Text(item.glyph) },
                            label = { Text(item.title) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (destination) {
                    DemoDestination.Balance -> BalanceScreen(
                        uiState = uiState,
                        onStart = controller::start,
                        onStop = controller::stop,
                        onRefresh = controller::refresh,
                        onClear = controller::clear,
                        onDebug = controller::showDebugInfo,
                        onStatus = controller::showStatusInfo,
                        onPurposeChange = controller::selectPurpose,
                    )

                    DemoDestination.SendReceive -> SendReceiveScreen(
                        uiState = uiState,
                        onReceiveClick = controller::onReceiveClick,
                        onReceiveAddressTypeChange = controller::setReceiveAddressType,
                        onAmountChange = controller::setAmount,
                        onAddressChange = controller::setAddress,
                        onFeePriorityChange = controller::setFeePriority,
                        onSendSourceChange = controller::setSendSource,
                        onLockTimeIntervalChange = controller::setLockTimeInterval,
                        onMaxClick = controller::onMaxClick,
                        onSendClick = controller::onSendClick,
                    )

                    DemoDestination.Transactions -> TransactionsScreen(
                        uiState = uiState,
                        onFilterChange = controller::setFilter,
                        onRawTransactionClick = controller::onRawTransactionClick,
                    )
                }
            }
        }

        uiState.statusInfo?.let {
            DumpDialog("Status Info", formatMapToString(it), controller::dismissDialog)
        }
        uiState.rawTransaction?.let {
            DumpDialog("Transaction HEX", it, controller::dismissDialog)
        }
    }
}

@Composable
private fun DumpDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Suppress("UNCHECKED_CAST")
private fun formatMapToString(
    status: Map<String, Any>,
    indentation: String = "",
    bullet: String = "",
    level: Int = 0,
): String {
    val sb = StringBuilder()
    status.toList().forEach { (key, value) ->
        val title = "$indentation$bullet$key"
        when (value) {
            is Map<*, *> -> {
                val formatted = formatMapToString(value as Map<String, Any>, "\t\t$indentation", " - ", level + 1)
                sb.append("$title:\n$formatted${if (level < 2) "\n" else ""}")
            }

            else -> sb.appendLine("$title: $value")
        }
    }

    val statusString = sb.trimEnd()
    return if (statusString.isEmpty()) "" else "$statusString\n"
}

package io.horizontalsystems.bitcoinkit.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.horizontalsystems.bitcoincore.models.TransactionFilterType
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.bitcoincore.models.TransactionInputInfo
import io.horizontalsystems.bitcoincore.models.TransactionOutputInfo
import io.horizontalsystems.dashkit.models.DashTransactionInfo
import io.horizontalsystems.hodler.HodlerOutputData
import io.horizontalsystems.hodler.HodlerPlugin
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val FILTERS = listOf(null) + TransactionFilterType.entries

@Composable
fun TransactionsScreen(
    uiState: DemoUiState,
    onFilterChange: (TransactionFilterType?) -> Unit,
    onRawTransactionClick: (String) -> Unit,
) {
    val enabled = uiState.kitControlsEnabled

    Column(Modifier.fillMaxWidth()) {
        SecondaryTabRow(selectedTabIndex = FILTERS.indexOf(uiState.filter)) {
            FILTERS.forEach { filter ->
                Tab(
                    selected = filter == uiState.filter,
                    onClick = { onFilterChange(filter) },
                    enabled = enabled,
                    text = { Text(filter?.name ?: "All") },
                )
            }
        }

        LazyColumn {
            itemsIndexed(uiState.transactions) { position, transaction ->
                TransactionRow(
                    transaction = transaction,
                    index = uiState.transactions.size - position,
                    enabled = enabled,
                    onRawClick = { onRawTransactionClick(transaction.transactionHash) },
                )
            }
        }
    }
}

@Composable
private fun TransactionRow(
    transaction: TransactionInfo,
    index: Int,
    enabled: Boolean,
    onRawClick: () -> Unit,
) {
    val background = if (index % 2 == 0) Color(0xFFDDDDDD) else Color.Transparent

    Row(
        modifier = Modifier.fillMaxWidth().background(background).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = summary(transaction, index),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onRawClick, enabled = enabled) { Text("Raw") }
    }
}

private fun summary(transaction: TransactionInfo, index: Int): String {
    val instant = if (transaction is DashTransactionInfo) {
        "\nInstant: ${transaction.instantTx.toString().uppercase(Locale.getDefault())}"
    } else {
        ""
    }

    return "#$index" +
        "\nStatus: ${transaction.status.name}, ${transaction.type.name}" +
        instant +
        "\nInputs: ${mapInputs(transaction.inputs)}" +
        "\nOutputs: ${mapOutputs(transaction.outputs)}" +
        "\nAmount: ${transaction.amount.formatSatoshi()}" +
        "\nFee: ${transaction.fee?.formatSatoshi() ?: "n/a"}" +
        "\nTx hash: ${transaction.transactionHash}" +
        "\nTx index: ${transaction.transactionIndex}" +
        "\nBlock: ${transaction.blockHeight}" +
        "\nTimestamp: ${transaction.timestamp}" +
        "\nDate: ${formatDate(transaction.timestamp)}" +
        "\nConflicting tx hash: ${transaction.conflictingTxHash}"
}

private fun mapOutputs(list: List<TransactionOutputInfo>) = list.joinToString("") { output ->
    val sb = StringBuilder()
    sb.append("\n- address: ${output.address}")
    sb.append("\n  value: ${output.value}")
    sb.append("\n  mine: ${output.mine}")
    sb.append("\n  change: ${output.changeOutput}")
    sb.append("\n  memo: ${output.memo}")

    if (output.pluginId == HodlerPlugin.id) {
        (output.pluginData as? HodlerOutputData)?.let { hodlerData ->
            hodlerData.approxUnlockTime?.let { lockedUntilApprox ->
                sb.append(
                    "\n  * Locked: ${hodlerData.lockTimeInterval.name}, " +
                        "approx until ${formatDate(lockedUntilApprox)}"
                )
            }
            sb.append("\n  * Address: ${hodlerData.addressString}")
            sb.append("\n  * Value: ${output.value}")
        }
    }
    sb.toString()
}

private fun mapInputs(list: List<TransactionInputInfo>) = list.joinToString("") { input ->
    "\n- address: ${input.address}" +
        "\n  value: ${input.value}" +
        "\n  mine: ${input.mine}"
}

private fun formatDate(timestamp: Long): String = DateFormat.getInstance().format(Date(timestamp * 1000))

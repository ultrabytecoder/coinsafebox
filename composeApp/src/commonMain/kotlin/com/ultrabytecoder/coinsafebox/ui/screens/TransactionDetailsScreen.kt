package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDownLeft
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ArrowUpRight
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Repeat
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.TransactionDirection
import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import com.ultrabytecoder.coinsafebox.domain.model.TransactionStatus
import com.ultrabytecoder.coinsafebox.ui.util.formatAmount
import com.ultrabytecoder.coinsafebox.ui.util.formatTransactionTime
import com.ultrabytecoder.coinsafebox.ui.viewmodel.TransactionDetailsUiState
import com.ultrabytecoder.coinsafebox.ui.viewmodel.TransactionDetailsViewModel
import kotlinx.coroutines.delay

private val IncomingColor = Color(0xFF4CAF50)
private val OutgoingColor = Color(0xFFF44336)
private val SelfColor = Color(0xFF9E9E9E)
private val PendingColor = Color(0xFFFFB300)
private val FailedColor = Color(0xFFF44336)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailsScreen(
    navController: NavController,
    viewModel: TransactionDetailsViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        when (val s = state) {
            TransactionDetailsUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            is TransactionDetailsUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = s.message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
            is TransactionDetailsUiState.Loaded -> {
                TransactionContent(
                    state = s,
                    paddingValues = paddingValues
                )
            }
        }
    }
}

@Composable
private fun TransactionContent(
    state: TransactionDetailsUiState.Loaded,
    paddingValues: PaddingValues
) {
    val tx = state.transaction
    val account = state.account

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TxHeaderCard(tx, account)
        TxDetailsCard(tx, account)
    }
}

@Composable
private fun TxHeaderCard(tx: TransactionInfo, account: AccountInfo?) {
    val directionColor = when (tx.direction) {
        TransactionDirection.INCOMING -> IncomingColor
        TransactionDirection.OUTGOING -> OutgoingColor
        TransactionDirection.SELF -> SelfColor
    }
    val amountPrefix = when (tx.direction) {
        TransactionDirection.INCOMING -> "+"
        TransactionDirection.OUTGOING -> "-"
        TransactionDirection.SELF -> ""
    }
    val symbol = account?.symbol ?: ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(directionColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (tx.direction) {
                        TransactionDirection.INCOMING -> FeatherIcons.ArrowDownLeft
                        TransactionDirection.OUTGOING -> FeatherIcons.ArrowUpRight
                        TransactionDirection.SELF -> FeatherIcons.Repeat
                    },
                    contentDescription = tx.direction.name,
                    tint = directionColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "$amountPrefix${formatTxAmount(tx, account)} $symbol".trim(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = directionColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            StatusChip(tx.status)
        }
    }
}

@Composable
private fun StatusChip(status: TransactionStatus) {
    val (label, color) = when (status) {
        TransactionStatus.PENDING -> "Pending" to PendingColor
        TransactionStatus.CONFIRMED -> "Confirmed" to IncomingColor
        TransactionStatus.FAILED -> "Failed" to FailedColor
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TxDetailsCard(tx: TransactionInfo, account: AccountInfo?) {
    val clipboardManager = LocalClipboardManager.current
    var copiedValue by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedValue) {
        if (copiedValue != null) {
            delay(2000)
            copiedValue = null
        }
    }

    val symbol = account?.symbol ?: ""
    val directionLabel = when (tx.direction) {
        TransactionDirection.INCOMING -> "Incoming"
        TransactionDirection.OUTGOING -> "Outgoing"
        TransactionDirection.SELF -> "Self"
    }
    val statusLabel = when (tx.status) {
        TransactionStatus.PENDING -> "Pending"
        TransactionStatus.CONFIRMED -> "Confirmed"
        TransactionStatus.FAILED -> "Failed"
    }
    val amountText = "${formatTxAmount(tx, account)} $symbol".trim()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DetailRow("Direction", directionLabel)
            DetailRow("Status", statusLabel)
            DetailRow("Amount", amountText)

            tx.fee?.let {
                DetailRow("Fee", it)
            } ?: DetailRow("Fee", "Not available")

            tx.counterpartyAddress?.let { address ->
                CopyableRow(
                    label = "Destination address",
                    value = address,
                    copied = copiedValue == address,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(address))
                        copiedValue = address
                    }
                )
            }

            DetailRow("Timestamp", formatTransactionTime(tx.timestamp))

            DetailRow("Block height", tx.blockHeight?.toString() ?: "Pending")

            CopyableRow(
                label = "Transaction hash",
                value = tx.txHash,
                copied = copiedValue == tx.txHash,
                onCopy = {
                    clipboardManager.setText(AnnotatedString(tx.txHash))
                    copiedValue = tx.txHash
                }
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CopyableRow(
    label: String,
    value: String,
    copied: Boolean,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value.midEllipsis(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(
            onClick = onCopy,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                contentDescription = "Copy $label",
                modifier = Modifier.size(14.dp),
                tint = if (copied) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun String.midEllipsis(): String {
    if (length <= 20) return this
    val edge = 8
    return "${take(edge)}...${takeLast(edge)}"
}

private fun formatTxAmount(tx: TransactionInfo, account: AccountInfo?): String {
    val type = account?.type ?: return tx.amount
    return formatAmount(tx.amount, type, tx.chainData)
}
package com.ultrabytecoder.coinsafebox.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDownLeft
import compose.icons.feathericons.ArrowUpRight
import compose.icons.feathericons.Repeat
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.TransactionDirection
import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import com.ultrabytecoder.coinsafebox.ui.util.formatAmount
import com.ultrabytecoder.coinsafebox.ui.util.formatTransactionTime

private val IncomingColor = Color(0xFF4CAF50)
private val OutgoingColor = Color(0xFFF44336)
private val SelfColor = Color(0xFF9E9E9E)

@Composable
fun TransactionItem(
    tx: TransactionInfo,
    accountType: AccountType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Direction icon with background
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
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
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Counterparty address and time
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tx.counterpartyAddress?.let {
                        "${it.take(6)}...${it.takeLast(4)}"
                    } ?: "Unknown",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatTransactionTime(tx.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Amount with direction color
            Text(
                text = "$amountPrefix${formatAmount(tx.amount, accountType, tx.chainData)}",
                style = MaterialTheme.typography.titleMedium,
                color = directionColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

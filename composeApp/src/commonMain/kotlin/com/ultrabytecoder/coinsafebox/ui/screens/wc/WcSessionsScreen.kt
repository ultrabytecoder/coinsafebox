package com.ultrabytecoder.coinsafebox.ui.screens.wc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcSession
import com.ultrabytecoder.coinsafebox.ui.util.SecureScreen
import com.ultrabytecoder.coinsafebox.ui.viewmodel.WcSessionsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Link
import kotlin.time.Clock

private fun shortCaip(caip: String): String {
    val parts = caip.split(":")
    if (parts.size != 3) return caip
    val address = parts[2]
    val short = if (address.length > 12) address.take(6) + "…" + address.takeLast(4) else address
    return "${parts[0]}:${parts[1]}:$short"
}

private fun formatRemaining(expirySeconds: Long): String {
    val nowSeconds = Clock.System.now().toEpochMilliseconds() / 1000
    val remaining = (expirySeconds - nowSeconds).coerceAtLeast(0)
    return when {
        remaining < 60 -> "less than a minute"
        remaining < 3600 -> "${remaining / 60} min"
        remaining < 86_400 -> "${remaining / 3600} hours"
        else -> "${remaining / 86_400} days"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WcSessionsScreen(
    navController: NavController,
    viewModel: WcSessionsViewModel
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    SecureScreen {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("WalletConnect") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No connected dApps.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                    items(sessions, key = { it.topic }) { session ->
                        SessionCard(session = session, onDisconnect = { viewModel.disconnect(session.topic) })
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: WcSession,
    onDisconnect: () -> Unit
) {
    val metadata = session.proposerMetadata
    val accounts = session.namespaces.values
        .mapNotNull { it.accounts }
        .flatten()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    FeatherIcons.Link,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    metadata.name.ifBlank { "Unknown dApp" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (metadata.url.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    metadata.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (accounts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                accounts.forEach { caip ->
                    Text(
                        shortCaip(caip),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Expires in ${formatRemaining(session.expiry)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onDisconnect) {
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

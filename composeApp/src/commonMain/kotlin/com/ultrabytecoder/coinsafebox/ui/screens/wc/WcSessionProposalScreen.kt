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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcProposal
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.ui.util.SecureScreen
import com.ultrabytecoder.coinsafebox.ui.viewmodel.WcSessionProposalViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Globe

private fun shortAddress(address: String): String =
    if (address.length > 12) address.take(6) + "…" + address.takeLast(4) else address

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WcSessionProposalScreen(
    navController: NavController,
    viewModel: WcSessionProposalViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.done) {
        if (state.done) navController.popBackStack()
    }

    SecureScreen {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Connection request") },
                    navigationIcon = {
                        IconButton(onClick = { if (!state.busy) navController.popBackStack() }) {
                            Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    when {
                        state.isLoading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        state.proposal == null -> {
                            Text(
                                state.error ?: "Proposal not available.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        else -> {
                            val proposal = state.proposal!!
                            DappInfoCard(proposal)

                            Spacer(modifier = Modifier.height(16.dp))

                            RequestedChainsSection(proposal)

                            if (state.chainMismatch) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        "This dApp only asks for chains this wallet is not " +
                                            "configured for. This build runs on chain " +
                                            "${viewModel.chainId}, so the connection cannot be approved.",
                                        modifier = Modifier.padding(16.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                "Share your accounts",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            if (state.accounts.isEmpty()) {
                                Text(
                                    "This wallet has no ETH accounts to share.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            state.accounts.forEach { account ->
                                AccountRow(
                                    account = account,
                                    checked = (account.address ?: "") in state.selectedAddresses,
                                    onToggle = {
                                        account.address?.let { viewModel.toggleAccount(it) }
                                    }
                                )
                            }

                            state.error?.let { error ->
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                if (!state.isLoading && state.proposal != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.reject() },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("Reject") }
                        Button(
                            onClick = { viewModel.approve() },
                            enabled = !state.busy && !state.chainMismatch && state.selectedAddresses.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (state.busy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Approve & Connect")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DappInfoCard(proposal: WcProposal) {
    val metadata = proposal.proposerMetadata
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
                    FeatherIcons.Globe,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        metadata.name.ifBlank { "Unknown dApp" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        metadata.url.ifBlank { "No URL provided" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (metadata.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    metadata.description,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun RequestedChainsSection(proposal: WcProposal) {
    val chains = (
        proposal.requiredNamespaces.values.mapNotNull { it.chains }.flatten() +
            proposal.optionalNamespaces.values.mapNotNull { it.chains }.flatten()
        ).distinct()
    if (chains.isEmpty()) return
    Text(
        "Requested chains",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        chains.joinToString(", "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun AccountRow(
    account: AccountInfo,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                account.name,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                shortAddress(account.address ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

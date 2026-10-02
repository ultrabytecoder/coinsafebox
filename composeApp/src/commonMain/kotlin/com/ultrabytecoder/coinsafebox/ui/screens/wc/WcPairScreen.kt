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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ultrabytecoder.coinsafebox.navigation.Screen
import com.ultrabytecoder.coinsafebox.ui.screens.rememberQrScannerLauncher
import com.ultrabytecoder.coinsafebox.ui.viewmodel.WcPairViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WcPairScreen(
    navController: NavController,
    walletId: Long,
    viewModel: WcPairViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingProposal by viewModel.wcController.proposalHolder.pendingProposal.collectAsStateWithLifecycle()
    var uri by remember { mutableStateOf("") }

    LaunchedEffect(state, pendingProposal) {
        val proposal = pendingProposal
        if (state is WcPairViewModel.State.ProposalReady && proposal != null) {
            navController.navigate(Screen.WcSessionProposal(proposal.id, walletId)) {
                popUpTo(Screen.WcPair::class) { inclusive = true }
            }
        }
    }

    // Scanning a QR is an explicit intent to connect, so pair immediately.
    val launchScanner = rememberQrScannerLauncher { result ->
        if (result != null) {
            uri = result.trim()
            viewModel.pair(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect to dApp") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
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
                .padding(16.dp)
        ) {
            Text(
                "Scan the WalletConnect QR code shown by the dApp, or paste its connection link below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (val s = state) {
                is WcPairViewModel.State.Idle -> {
                    OutlinedTextField(
                        value = uri,
                        onValueChange = { uri = it },
                        label = { Text("WalletConnect link (wc:…)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.pair(uri) },
                            enabled = uri.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Connect") }
                        if (launchScanner != null) {
                            OutlinedButton(
                                onClick = { launchScanner() },
                                modifier = Modifier.weight(1f)
                            ) { Text("Scan QR Code") }
                        }
                    }
                }

                is WcPairViewModel.State.Pairing -> {
                    WaitingRow("Connecting…")
                }

                is WcPairViewModel.State.WaitingForProposal -> {
                    WaitingRow("Waiting for the dApp to send a connection request…")
                }

                is WcPairViewModel.State.ProposalReady -> {
                    WaitingRow("Connection request received…")
                }

                is WcPairViewModel.State.Failed -> {
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { viewModel.tryAgain() }) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun WaitingRow(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

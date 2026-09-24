package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import com.ultrabytecoder.coinsafebox.ui.viewmodel.CreateWalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevealMnemonicScreen(
    viewModel: CreateWalletViewModel,
    onBack: () -> Unit
) {
    val mnemonic by viewModel.finalMnemonic.collectAsState()
    val createError by viewModel.createError.collectAsState()
    val isCreating by viewModel.isCreating.collectAsState()
    var mnemonicVisible by remember { mutableStateOf(false) }

    // Generation runs once in the ViewModel's viewModelScope on
    // Dispatchers.Default, so composition is never blocked by
    // CSPRNG/HMAC/BIP-39 work. A stale error from a previous attempt is
    // cleared when the screen is entered.
    LaunchedEffect(Unit) {
        viewModel.clearCreateError()
        viewModel.generateFinalMnemonic()
    }



    // The immutable String the text field needs is allocated only when the
    // visibility actually changes — never on unrelated recompositions.
    val mnemonicText = remember(mnemonic, mnemonicVisible) {
        if (mnemonicVisible) mnemonic?.concatToString() else null
    }
    val hiddenPlaceholder = remember(mnemonic) {
        "\u2022".repeat(mnemonic?.size ?: 0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your recovery phrase") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Write this phrase down and store it safely. " +
                            "It is the ONLY way to recover your wallet.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (mnemonic == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = mnemonicText ?: hiddenPlaceholder,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { mnemonicVisible = !mnemonicVisible }
                        ) {
                            Icon(
                                imageVector = if (mnemonicVisible) FeatherIcons.EyeOff else FeatherIcons.Eye,
                                contentDescription = if (mnemonicVisible) "Hide" else "Show"
                            )
                        }
                    }
                }

                createError?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        viewModel.createWalletFromGenerated()
                    },
                    enabled = !isCreating,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Create Wallet")
                }
            }
        }
    }
}

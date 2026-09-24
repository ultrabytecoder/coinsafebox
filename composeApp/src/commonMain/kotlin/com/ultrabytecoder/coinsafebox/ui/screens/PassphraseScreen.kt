package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.AppKeyboard
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.SecureOutlinedTextField
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.LocalKeyboardController
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.SecureTargetAdapter
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.rememberKeyboardController
import com.ultrabytecoder.coinsafebox.ui.util.SecureTextFieldState
import com.ultrabytecoder.coinsafebox.ui.viewmodel.CreateWalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassphraseScreen(
    viewModel: CreateWalletViewModel,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    var usePassphrase by rememberSaveable { mutableStateOf(viewModel.hasPassphrase) }
    var passphraseError by remember { mutableStateOf<String?>(null) }
    val passphraseState = remember { SecureTextFieldState() }
    val passphraseTarget = remember {
        SecureTargetAdapter(
            state = passphraseState,
            maxLength = 256,
            onValueChanged = { passphraseError = null }
        )
    }
    val passphraseConfirmState = remember { SecureTextFieldState() }
    val passphraseConfirmTarget = remember {
        SecureTargetAdapter(
            state = passphraseConfirmState,
            maxLength = 256,
            onValueChanged = { passphraseError = null }
        )
    }
    val continueAction: () -> Unit = {
        when {
            usePassphrase && !passphraseState.matches(passphraseConfirmState) ->
                passphraseError = "Passphrases do not match"
            usePassphrase && passphraseState.isBlank() ->
                passphraseError = "Passphrase cannot be empty"
            else -> {
                passphraseError = null
                if (usePassphrase) {
                    // Hand off a fresh copy (the ViewModel takes ownership), then
                    // scrub the local buffers so the passphrase does not linger on
                    // the backstack after navigating forward.
                    viewModel.setPassphrase(passphraseState.toCharArray())
                    passphraseState.wipe()
                    passphraseConfirmState.wipe()
                } else {
                    viewModel.clearPassphrase()
                }
                onNext()
            }
        }
    }
    val controller = rememberKeyboardController(onAction = continueAction)

    DisposableEffect(Unit) {
        onDispose {
            passphraseState.wipe()
            passphraseConfirmState.wipe()
        }
    }

    CompositionLocalProvider(LocalKeyboardController provides controller) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Passphrase") },
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Do you want to add an additional layer of protection using a passphrase?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    SegmentedSingleChoice(
                        options = listOf(
                            false to "No, skip",
                            true to "Yes, add passphrase"
                        ),
                        selected = usePassphrase,
                        onSelected = {
                            usePassphrase = it
                            if (!it) {
                                passphraseState.wipe()
                                passphraseConfirmState.wipe()
                                passphraseError = null
                            }
                        }
                    )

                    if (usePassphrase) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Important: the passphrase is NOT stored and cannot be recovered.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "If you forget it, your wallet is permanently unrecoverable, even with the correct mnemonic. " +
                                        "A small typo creates a completely different wallet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }

                        SecureOutlinedTextField(
                            target = passphraseTarget,
                            label = { Text("Passphrase") },
                            singleLine = true,
                            isError = passphraseError != null,
                            supportingText = passphraseError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        SecureOutlinedTextField(
                            target = passphraseConfirmTarget,
                            label = { Text("Confirm passphrase") },
                            singleLine = true,
                            isError = passphraseError != null,
                            supportingText = passphraseError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = continueAction,
                        enabled = !usePassphrase || !passphraseState.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Continue")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    AppKeyboard()
                }
            }
        }
    }
}

@Composable
private fun <T> SegmentedSingleChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Surface(
                onClick = { onSelected(value) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}


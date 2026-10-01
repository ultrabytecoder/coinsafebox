package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import com.ultrabytecoder.coinsafebox.security.SecureMnemonicCode
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.AppKeyboard
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.MnemonicSuggestionBar
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.SecureOutlinedTextField
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.LocalKeyboardController
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.SecureTargetAdapter
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.rememberKeyboardController
import com.ultrabytecoder.coinsafebox.ui.util.SecureTextFieldState
import com.ultrabytecoder.coinsafebox.ui.viewmodel.CreateWalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateWalletSetupScreen(
    viewModel: CreateWalletViewModel,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    var walletName by rememberSaveable { mutableStateOf(viewModel.walletNameValue) }
    // Two-step flow: step 0 = mode + wallet name (system IME, for good name entry);
    // step 1 = mode-specific secure fields (on-screen keyboard). Splitting keeps the
    // system keyboard and the secure keyboard from sharing a screen (which would
    // conflict on iOS and double-show keyboards). rememberSaveable so a mid-flow
    // rotation / config change doesn't kick the user back to step 0.
    var step by rememberSaveable { mutableStateOf(0) }
    val focusManager = LocalFocusManager.current

    var mnemonicError by remember { mutableStateOf<String?>(null) }
    var passphraseError by remember { mutableStateOf<String?>(null) }
    val createError by viewModel.createError.collectAsState()
    val isCreating by viewModel.isCreating.collectAsState()

    val mode by viewModel.mode.collectAsState()
    val wordCount by viewModel.wordCount.collectAsState()
    val useGesture by viewModel.useGesture.collectAsState()

    val mnemonicState = remember { SecureTextFieldState() }
    val mnemonicTarget = remember {
        SecureTargetAdapter(
            state = mnemonicState,
            maxLength = 300,
            maxLines = 5,
            isSingleLine = false,
            onValueChanged = {
                mnemonicError = null
                viewModel.clearCreateError()
            }
        )
    }

    var usePassphrase by rememberSaveable { mutableStateOf(false) }
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

    val nextAction: () -> Unit = {
        if (mode == CreateWalletViewModel.Mode.GENERATE_NEW) {
            if (walletName.isNotBlank()) onNext()
        } else {
            when {
                usePassphrase && !passphraseState.matches(passphraseConfirmState) ->
                    passphraseError = "Passphrases do not match"
                else -> {
                    passphraseError = null
                    // `mnemonicChars`/`passphraseChars` are fresh copies handed to the
                    // ViewModel, which takes ownership and wipes them in its own
                    // coroutine — do NOT wipe them here (would race the
                    // Dispatchers.Default write). Only scrub our own buffers.
                    val mnemonicChars = mnemonicState.trimmedCopy()
                    val passphraseChars =
                        if (usePassphrase) passphraseState.toCharArray() else CharArray(0)
                    viewModel.setPassphrase(passphraseChars)
                    viewModel.createWalletFromMnemonic(mnemonicChars)
                    mnemonicState.wipe()
                    passphraseState.wipe()
                    passphraseConfirmState.wipe()
                }
            }
        }
    }
    val controller = rememberKeyboardController(onAction = nextAction)

    // Keep the keyboard inert while wallet creation is in flight.
    LaunchedEffect(isCreating) {
        controller.setInputEnabled(!isCreating)
    }

    DisposableEffect(Unit) {
        onDispose {
            mnemonicState.wipe()
            passphraseState.wipe()
            passphraseConfirmState.wipe()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.clearCreateError()
    }

    CompositionLocalProvider(LocalKeyboardController provides controller) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Create Wallet") },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (step == 0) {
                                    focusManager.clearFocus()
                                    onBack()
                                } else {
                                    step = 0
                                    controller.hide()
                                }
                            },
                            enabled = !isCreating
                        ) {
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Set up your wallet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (mode == CreateWalletViewModel.Mode.GENERATE_NEW)
                            "Generate a new recovery phrase"
                        else
                            "Restore from an existing recovery phrase",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    when (step) {
                        0 -> {
                    SegmentedSingleChoice(
                        options = listOf(
                            CreateWalletViewModel.Mode.GENERATE_NEW to "Generate new",
                            CreateWalletViewModel.Mode.RESTORE_EXISTING to "Restore existing"
                        ),
                        selected = mode,
                        onSelected = { newMode ->
                            if (newMode != mode) {
                                if (mode == CreateWalletViewModel.Mode.RESTORE_EXISTING) {
                                    mnemonicState.wipe()
                                    passphraseState.wipe()
                                    passphraseConfirmState.wipe()
                                    mnemonicError = null
                                    passphraseError = null
                                    usePassphrase = false
                                }
                                viewModel.setMode(newMode)
                            }
                        },
                        enabled = !isCreating
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = walletName,
                        onValueChange = { walletName = it; viewModel.setWalletName(it) },
                        label = { Text("Wallet name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                        }
                        else -> {
                    if (mode == CreateWalletViewModel.Mode.GENERATE_NEW) {
                        Text(
                            "Phrase length",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SegmentedSingleChoice(
                            options = SecureMnemonicCode.SUPPORTED_WORD_COUNTS.map { it to "$it words" },
                            selected = wordCount,
                            onSelected = { viewModel.setWordCount(it) },
                            enabled = !isCreating
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isCreating) { viewModel.setUseGesture(!useGesture) }
                        ) {
                            Checkbox(
                                checked = useGesture,
                                onCheckedChange = null
                            )
                            Text("Add extra entropy (draw a gesture)")
                        }
                        Text(
                            "Optional: your gesture is mixed with the system random entropy. " +
                                "It is never stored — you only need it once, right now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Deliberately NOT masked: during restore the user types a phrase
                        // they already know and must visually verify every word — one
                        // unnoticed typo means an unrecoverable wallet. Exposure is
                        // controlled elsewhere: no system IME, semantics { password() }
                        // (screen readers never announce it), and the buffer is wiped on
                        // background (ON_STOP), screen dispose, and after consumption.
                        SecureOutlinedTextField(
                            target = mnemonicTarget,
                            label = { Text("Mnemonic") },
                            masked = false,
                            isError = mnemonicError != null,
                            supportingText = mnemonicError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                            minLines = 3,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        if (controller.activeTarget == mnemonicTarget) {
                            MnemonicSuggestionBar(
                                text = mnemonicTarget.text,
                                onPick = { word -> mnemonicTarget.replaceTrailingWord(word) },
                                enabled = !isCreating
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isCreating) {
                                    usePassphrase = !usePassphrase
                                    if (!usePassphrase) {
                                        passphraseState.wipe()
                                        passphraseConfirmState.wipe()
                                        passphraseError = null
                                    }
                                }
                        ) {
                            Checkbox(
                                checked = usePassphrase,
                                onCheckedChange = null
                            )
                            Text("I'm using a passphrase")
                        }

                        if (usePassphrase) {
                            SecureOutlinedTextField(
                                target = passphraseTarget,
                                label = { Text("Passphrase") },
                                singleLine = true,
                                revealable = true,
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
                                revealable = true,
                                isError = passphraseError != null,
                                supportingText = passphraseError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (step) {
                        0 -> {
                            Button(
                                onClick = {
                                    // Clear focus on the wallet-name field so the system IME
                                    // is dismissed (Android can leave it orphaned when the
                                    // focused field leaves composition without explicit focus
                                    // clearing), before showing the secure-keyboard step.
                                    focusManager.clearFocus()
                                    step = 1
                                },
                                enabled = walletName.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Next")
                            }
                        }
                        else -> {
                            if (mode == CreateWalletViewModel.Mode.GENERATE_NEW) {
                                Button(
                                    onClick = nextAction,
                                    enabled = walletName.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Next")
                                }
                            } else {
                                createError?.let {
                                    Text(
                                        it,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                Button(
                                    onClick = nextAction,
                                    enabled = walletName.isNotBlank()
                                        && (mnemonicState.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() } in SecureMnemonicCode.SUPPORTED_WORD_COUNTS)
                                        && (!usePassphrase || !passphraseState.isBlank())
                                        && !isCreating,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Create Wallet")
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            AppKeyboard()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> SegmentedSingleChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Surface(
                onClick = { onSelected(value) },
                enabled = enabled,
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


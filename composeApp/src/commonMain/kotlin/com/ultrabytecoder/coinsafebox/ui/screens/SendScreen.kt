package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Camera
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ultrabytecoder.coinsafebox.navigation.Screen
import com.ultrabytecoder.coinsafebox.ui.theme.AuroraPrimary
import com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams
import com.ultrabytecoder.coinsafebox.domain.model.FeeEstimation
import com.ultrabytecoder.coinsafebox.domain.model.FeePresets
import com.ultrabytecoder.coinsafebox.ui.util.BalanceSufficiency
import com.ultrabytecoder.coinsafebox.ui.util.evaluateBalanceSufficiency
import com.ultrabytecoder.coinsafebox.ui.util.formatFeeChipRate
import com.ultrabytecoder.coinsafebox.ui.util.formatFeeDetail
import com.ultrabytecoder.coinsafebox.ui.util.formatFiat
import com.ultrabytecoder.coinsafebox.ui.util.formatGwei
import com.ultrabytecoder.coinsafebox.ui.util.parseGweiToMilliGwei
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.AppKeyboard
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.SecureOutlinedTextField
import com.ultrabytecoder.coinsafebox.ui.keyboard.model.KeyboardLayoutType
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.KeyboardTarget
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.LocalKeyboardController
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.MutableStateTarget
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.rememberKeyboardController
import com.ultrabytecoder.coinsafebox.ui.viewmodel.FeeSelectionMode
import com.ultrabytecoder.coinsafebox.ui.viewmodel.SendPhase
import com.ultrabytecoder.coinsafebox.ui.viewmodel.SendViewModel

/**
 * Cheap plausibility gate for a pasted recipient address. Enforces an alphanumeric
 * character set (covers base58, bech32, hex — all chains) plus a sane length bound,
 * so clipboard contents with symbols, whitespace, or zero-width / multi-line junk are
 * rejected before they enter the field. Chain-specific validation still happens on
 * submit; this only filters obviously-bad paste payloads.
 */
private fun isPlausibleAddress(value: String): Boolean =
    value.length in 8..120 && value.all { it.isLetterOrDigit() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    navController: NavController,
    viewModel: SendViewModel
) {
    val account by viewModel.account.collectAsState()
    val fee by viewModel.fee.collectAsState()
    val feeFiat by viewModel.feeFiat.collectAsState()
    val totalFiat by viewModel.totalFiat.collectAsState()
    val fiatCurrency by viewModel.fiatCurrency.collectAsState()
    val feeError by viewModel.feeError.collectAsState()
    val feePresets by viewModel.feePresets.collectAsState()
    val selectedFeeMode by viewModel.selectedFeeMode.collectAsState()
    val customBtcFeeRate by viewModel.customBtcFeeRate.collectAsState()
    val customEthPriorityFee by viewModel.customEthPriorityFee.collectAsState()
    val customEthMaxFee by viewModel.customEthMaxFee.collectAsState()
    val customTrc20FeeLimit by viewModel.customTrc20FeeLimit.collectAsState()
    val validationError by viewModel.validationError.collectAsState()
    val phase by viewModel.phase.collectAsState()
    val hasPassphrase by viewModel.hasPassphrase.collectAsState()
    val isReadOnly by viewModel.isReadOnly.collectAsState()
    val sendError by viewModel.sendError.collectAsState()
    val sendResult by viewModel.sendResult.collectAsState()
    var address by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    val signing = phase == SendPhase.SIGNING
    var btcFeeText by remember { mutableStateOf(customBtcFeeRate.toString()) }
    var ethPriorityText by remember { mutableStateOf(formatGwei(customEthPriorityFee)) }
    var ethMaxFeeText by remember { mutableStateOf(formatGwei(customEthMaxFee)) }
    var trc20FeeText by remember { mutableStateOf(customTrc20FeeLimit.toString()) }

    // Custom-secure-keyboard targets. Each wraps the plain `mutableStateOf` strings
    // above so ALL on-screen input flows through the in-app keyboard — no system IME
    // InputConnection is ever created for these fields (no keylogger/IME side-channel).
    // The fee setters also push the value to the ViewModel, matching the previous
    // OutlinedTextField onValueChange behavior.
    val addressTarget = remember { MutableStateTarget(getter = { address }, setter = { address = it }) }
    val amountTarget = remember { MutableStateTarget(getter = { amount }, setter = { amount = it }) }
    val btcFeeTarget = remember {
        MutableStateTarget(
            getter = { btcFeeText },
            setter = { btcFeeText = it; viewModel.setCustomBtcFeeRate(it.toLongOrNull() ?: 0L) }
        )
    }
    val ethPriorityTarget = remember {
        MutableStateTarget(
            getter = { ethPriorityText },
            setter = {
                ethPriorityText = it
                viewModel.setCustomEthFees(parseGweiToMilliGwei(it) ?: 0L, parseGweiToMilliGwei(ethMaxFeeText) ?: 0L)
            }
        )
    }
    val ethMaxFeeTarget = remember {
        MutableStateTarget(
            getter = { ethMaxFeeText },
            setter = {
                ethMaxFeeText = it
                viewModel.setCustomEthFees(parseGweiToMilliGwei(ethPriorityText) ?: 0L, parseGweiToMilliGwei(it) ?: 0L)
            }
        )
    }
    val trc20FeeTarget = remember {
        MutableStateTarget(
            getter = { trc20FeeText },
            setter = { trc20FeeText = it; viewModel.setCustomTrc20FeeLimit(it.toLongOrNull() ?: 0L) }
        )
    }
    val mnemonicTarget = remember {
        MutableStateTarget(
            getter = { viewModel.mnemonicField.text },
            setter = { viewModel.mnemonicField.update(it) }
        )
    }
    val passphraseTarget = remember {
        MutableStateTarget(
            getter = { viewModel.passphraseField.text },
            setter = { viewModel.passphraseField.update(it) }
        )
    }
    val keyboardController = rememberKeyboardController()

    // Sync local UI text fields whenever the ViewModel custom flows update
    LaunchedEffect(customBtcFeeRate, customEthPriorityFee, customEthMaxFee, customTrc20FeeLimit) {
        btcFeeText = customBtcFeeRate.toString()
        ethPriorityText = formatGwei(customEthPriorityFee)
        ethMaxFeeText = formatGwei(customEthMaxFee)
        trc20FeeText = customTrc20FeeLimit.toString()
    }

    // Pre-fill the ViewModel custom flows from presets or provider-applied values
    // so the (read-only or editable) fields display the correct values.
    LaunchedEffect(selectedFeeMode, feePresets, fee?.appliedParams) {
        val params = when (selectedFeeMode) {
            is FeeSelectionMode.Conservative -> feePresets?.slow
            is FeeSelectionMode.Balanced -> feePresets?.medium
            is FeeSelectionMode.Generous -> feePresets?.fast
            // For Auto, use the provider-applied params if available (e.g. after entering an amount).
            // Otherwise, fall back to the live values fetched for the Auto preset.
            is FeeSelectionMode.Auto -> fee?.appliedParams ?: feePresets?.auto ?: feePresets?.medium
            is FeeSelectionMode.Custom -> null // keep current custom values
        }
        params?.let { viewModel.syncCustomFieldsFromParams(it) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val launchScanner = rememberQrScannerLauncher { result ->
        if (result != null) {
            address = result
            // Programmatic fill: park the cursor at the end so a subsequent tap+type
            // appends rather than prepending to the scanned address.
            addressTarget.setCursor(result.length)
        }
    }

    val showFeeSection = viewModel.showFeeSection()

    // Re-read the fiat currency selection when (re)entering the screen.
    LaunchedEffect(Unit) { viewModel.refreshFiatCurrency() }

    // Navigate once a send (full or read-only) has succeeded.
    LaunchedEffect(sendResult) {
        val txid = sendResult
        if (txid != null) {
            viewModel.onSendResultConsumed()
            navController.navigate(Screen.TransactionSent(txid)) {
                popUpTo(Screen.AccountsList::class) { inclusive = false }
            }
        }
    }

    // Deterministic wipe of the mnemonic/passphrase buffers when leaving the screen.
    DisposableEffect(Unit) {
        onDispose { viewModel.clearSensitiveData() }
    }

    LaunchedEffect(amount, address, selectedFeeMode, btcFeeText, ethPriorityText, ethMaxFeeText, trc20FeeText) {
        if (amount.isNotBlank()) {
            try {
                val parsed = BigDecimal.parseString(amount)
                delay(300)
                viewModel.estimateFee(parsed, address.takeIf { it.isNotBlank() })
            } catch (_: Exception) {
            }
        } else {
            viewModel.clearFee()
        }
    }

    if (account == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Account not found")
        }
        return
    }
    val accountVal = account

    val parsedAmount = if (amount.isNotBlank()) {
        try { BigDecimal.parseString(amount) } catch (_: Exception) { null }
    } else null

    val feeVal = fee
    val feeErrVal = feeError
    // For token accounts (TRC20/ERC20), fee is in the native chain currency,
    // so adding amount + fee produces a meaningless number.
    val isTokenAccount = accountVal?.type?.isToken == true
    val total = if (!isTokenAccount && parsedAmount != null && feeVal?.totalCost != null) {
        parsedAmount.add(feeVal.totalCost)
    } else null

    // Pre-emptive, non-blocking warning for when the balance can't cover the send.
    // A pure derivation of already-collected state (mirrors how `total` is computed
    // above); the native/token comparison rules live in evaluateBalanceSufficiency.
    val balanceSufficiency = evaluateBalanceSufficiency(
        balanceRaw = accountVal!!.amount,
        parsedAmount = parsedAmount,
        feeEstimation = fee,
        isTokenAccount = isTokenAccount
    )

    // Hard block (disabled Send button) for full wallets when the balance can't
    // cover the send. Read-only wallets stay enabled so the recovery-phrase guard
    // remains reachable; it is verified before any on-chain operation.
    val sendEnabled = !signing &&
        (isReadOnly || balanceSufficiency == BalanceSufficiency.Sufficient)

    CompositionLocalProvider(LocalKeyboardController provides keyboardController) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Send") },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (phase == SendPhase.MNEMONIC_PROMPT) viewModel.cancelMnemonicPrompt()
                            else navController.popBackStack()
                        }) {
                            Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (phase == SendPhase.MNEMONIC_PROMPT) {
                        MnemonicSendPromptCard(
                            mnemonicTarget = mnemonicTarget,
                            showPassphrase = hasPassphrase,
                            passphraseTarget = passphraseTarget,
                            errorText = sendError,
                            onSignAndSend = { viewModel.confirmSend() },
                            onCancel = { viewModel.cancelMnemonicPrompt() }
                        )
                    } else {
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
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = "Available Balance",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = accountVal!!.amount,
                                style = MaterialTheme.typography.headlineSmall,
                                color = AuroraPrimary
                            )
                        }
                    }

                    // Placed right under the balance (not at the bottom of the form)
                    // so the warning is always in view and visually tied to the
                    // balance it refers to.
                    if (balanceSufficiency != BalanceSufficiency.Sufficient) {
                        Spacer(modifier = Modifier.height(12.dp))
                        InsufficientBalanceBanner(
                            message = insufficientBalanceMessage(accountVal!!.symbol, balanceSufficiency)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SecureOutlinedTextField(
                            target = addressTarget,
                            label = { Text("Recipient Address") },
                            placeholder = { Text("Enter or paste address") },
                            layoutType = KeyboardLayoutType.Qwerty,
                            masked = false,
                            singleLine = true,
                            pasteEnabled = true,
                            pasteValidator = { isPlausibleAddress(it) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        if (launchScanner != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = { launchScanner() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    FeatherIcons.Camera,
                                    contentDescription = "Scan QR",
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SecureOutlinedTextField(
                        target = amountTarget,
                        label = { Text("Amount") },
                        placeholder = { Text("0.00") },
                        layoutType = KeyboardLayoutType.Numeric,
                        supportsDecimal = true,
                        masked = false,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )

                    // Fee selection section
                    if (showFeeSection) {
                        Spacer(modifier = Modifier.height(16.dp))
                        FeeSelector(
                            selectedMode = selectedFeeMode,
                            onSelectMode = { viewModel.setSelectedFeeMode(it) },
                            feePresets = feePresets,
                            btcFeeTarget = btcFeeTarget,
                            ethPriorityTarget = ethPriorityTarget,
                            ethMaxFeeTarget = ethMaxFeeTarget,
                            trc20FeeTarget = trc20FeeTarget,
                            autoFeeFallback = feeVal?.usedFallbackFees == true,
                            accountType = accountVal!!.type,
                            onValidate = { viewModel.validateCustomFee() },
                            validationError = validationError
                        )
                    }

                    if (feeVal != null) {
                        Spacer(modifier = Modifier.height(12.dp))

                        // Use the params actually applied by the provider (e.g. from Auto mode RPC query)
                        val feeDetailLines = formatFeeDetail(feeVal.appliedParams)

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Network Fee",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = feeVal?.totalCost?.toPlainString() ?: "",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        if (feeFiat != null) {
                                            Text(
                                                text = formatFiat(feeFiat!!, fiatCurrency.code),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                                if (feeDetailLines != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Column {
                                        feeDetailLines.forEach { line ->
                                            Text(
                                                text = line,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                                if (total != null || totalFiat != null) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 6.dp),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Total",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Column(horizontalAlignment = Alignment.End) {
                                            // For token accounts the crypto total is meaningless
                                            // (token amount + native fee), show it as "—" there.
                                            Text(
                                                text = total?.toPlainString() ?: "—",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            if (totalFiat != null) {
                                                Text(
                                                    text = formatFiat(totalFiat!!, fiatCurrency.code),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (feeErrVal != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = feeErrVal,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    }
                }

                // Send / mnemonic controls pinned at bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (phase == SendPhase.MNEMONIC_PROMPT) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.cancelMnemonicPrompt() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                                enabled = !signing
                            ) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = { viewModel.confirmSend() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                                enabled = !signing
                            ) {
                                if (signing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Text("Sign & Send")
                                }
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                if (address.isNotBlank() && amount.isNotBlank()) {
                                    // Validate custom fee before sending
                                    if (selectedFeeMode is FeeSelectionMode.Custom && !viewModel.validateCustomFee()) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Please fix fee values")
                                        }
                                        return@Button
                                    }
                                    viewModel.requestSend(address, BigDecimal.parseString(amount))
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Please fill in all fields")
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = sendEnabled
                        ) {
                            if (signing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Send")
                            }
                        }
                    }
                }
                AppKeyboard()
            }
        }
    }
}

private data class FeeChipData(
    val mode: FeeSelectionMode,
    val label: String,
    val params: CustomFeeParams?
)

@Composable
private fun FeeSelector(
    selectedMode: FeeSelectionMode,
    onSelectMode: (FeeSelectionMode) -> Unit,
    feePresets: FeePresets?,
    btcFeeTarget: KeyboardTarget,
    ethPriorityTarget: KeyboardTarget,
    ethMaxFeeTarget: KeyboardTarget,
    trc20FeeTarget: KeyboardTarget,
    autoFeeFallback: Boolean,
    accountType: com.ultrabytecoder.coinsafebox.domain.model.AccountType,
    onValidate: () -> Boolean,
    validationError: String?
) {
    val parentChain = accountType.parentChain() ?: accountType
    val isBtc = parentChain is com.ultrabytecoder.coinsafebox.domain.model.AccountType.Btc
    val isEth = parentChain is com.ultrabytecoder.coinsafebox.domain.model.AccountType.Eth
    val isTrx = parentChain is com.ultrabytecoder.coinsafebox.domain.model.AccountType.Trx

    // TRC20 fee_limit values are safety caps, not speed bids — label them accordingly
    val slowLabel = if (isTrx) "Conservative" else "Slow"
    val mediumLabel = if (isTrx) "Balanced" else "Medium"
    val fastLabel = if (isTrx) "Generous" else "Fast"

    val chipModes = listOf(
        FeeChipData(FeeSelectionMode.Auto, "Auto", feePresets?.auto),
        FeeChipData(FeeSelectionMode.Conservative, slowLabel, feePresets?.slow),
        FeeChipData(FeeSelectionMode.Balanced, mediumLabel, feePresets?.medium),
        FeeChipData(FeeSelectionMode.Generous, fastLabel, feePresets?.fast),
        FeeChipData(FeeSelectionMode.Custom, "Custom", null)
    )

    // Fee selection visibility per chain:
    //   * BTC              -> Conservative, Balanced, Generous, Custom   (Auto hidden)
    //   * ETH              -> Auto, Conservative, Balanced, Generous, Custom
    //   * TRC20            -> Auto, Conservative, Balanced, Generous, Custom
    //   * native TRX       -> Auto only (disabled, no presets)
    //   * GRAM             -> selector hidden (no presets)
    val hasPresets = feePresets != null
    val isNativeTrx = isTrx && !hasPresets
    val visibleChipModes = when {
        isBtc -> chipModes.filter { it.mode !is FeeSelectionMode.Auto }
        isEth || (isTrx && hasPresets) -> chipModes
        else -> listOf(chipModes[0]) // Auto only
    }

    Text(
        text = "Fee Selection",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(8.dp))

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        visibleChipModes.forEach { chip ->
            val isSelected = selectedMode == chip.mode
            FilterChip(
                selected = isSelected,
                enabled = !isNativeTrx,
                onClick = { if (!isNativeTrx) onSelectMode(chip.mode) },
                label = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = chip.label,
                            style = MaterialTheme.typography.labelLarge
                        )
                        val rateText = formatFeeChipRate(chip.params)
                        if (rateText != null || chip.mode is FeeSelectionMode.Auto) {
                            Text(
                                text = rateText ?: "—",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    }

    if (isTrx) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (isNativeTrx) {
                "Network fee is auto-determined by bandwidth availability. Transfers with sufficient bandwidth cost 0 TRX."
            } else {
                "Higher limit = more safety margin, not faster confirmation."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Fee input fields — visible in every mode so the user can see the preset
    // or auto-applied values. Editable only in Custom mode.
    Spacer(modifier = Modifier.height(12.dp))

    val isEditable = selectedMode is FeeSelectionMode.Custom

    when {
        isBtc -> {
            SecureOutlinedTextField(
                target = btcFeeTarget,
                label = { Text("Fee Rate (sat/vB)") },
                placeholder = { Text("10") },
                layoutType = KeyboardLayoutType.Numeric,
                masked = false,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = isEditable,
                isError = validationError != null,
                supportingText = validationError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                shape = RoundedCornerShape(16.dp)
            )
        }
        isEth -> {
            SecureOutlinedTextField(
                target = ethPriorityTarget,
                label = { Text("Priority Fee (Gwei)") },
                placeholder = { Text("25") },
                layoutType = KeyboardLayoutType.Numeric,
                supportsDecimal = true,
                masked = false,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = isEditable,
                isError = validationError != null,
                supportingText = validationError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            SecureOutlinedTextField(
                target = ethMaxFeeTarget,
                label = { Text("Max Fee (Gwei)") },
                placeholder = { Text("35") },
                layoutType = KeyboardLayoutType.Numeric,
                supportsDecimal = true,
                masked = false,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = isEditable,
                shape = RoundedCornerShape(16.dp)
            )
        }
        isTrx && !isNativeTrx -> {
            val feeLimitSun = trc20FeeTarget.text.toLongOrNull()
            val feeTrxText = if (feeLimitSun != null && feeLimitSun >= 0) {
                val whole = feeLimitSun / 1_000_000
                val frac = (feeLimitSun % 1_000_000).toString().padStart(6, '0')
                "~$whole.$frac TRX"
            } else {
                "—"
            }
            SecureOutlinedTextField(
                target = trc20FeeTarget,
                label = { Text("Fee Limit (SUN)") },
                placeholder = { Text("35000000") },
                layoutType = KeyboardLayoutType.Numeric,
                masked = false,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = isEditable,
                isError = validationError != null,
                supportingText = {
                    Text(
                        text = feeTrxText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                shape = RoundedCornerShape(16.dp)
            )
        }
    }

    if (selectedMode is FeeSelectionMode.Auto && !isNativeTrx) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (autoFeeFallback) {
                "Live fee estimation unavailable — showing fallback values from network gas price"
            } else {
                "Values fetched live from the network at estimation time"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (autoFeeFallback) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MnemonicSendPromptCard(
    mnemonicTarget: KeyboardTarget,
    showPassphrase: Boolean,
    passphraseTarget: KeyboardTarget,
    errorText: String?,
    onSignAndSend: () -> Unit,
    onCancel: () -> Unit
) {
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
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Recovery Phrase Required",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "This wallet is read-only. Enter your recovery phrase to sign and send. It is wiped immediately after use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            SecureOutlinedTextField(
                target = mnemonicTarget,
                label = { Text("Recovery Phrase") },
                placeholder = { Text("your twelve or twenty-four word phrase") },
                layoutType = KeyboardLayoutType.Qwerty,
                masked = false,
                singleLine = false,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )
            if (showPassphrase) {
                Spacer(modifier = Modifier.height(12.dp))
                SecureOutlinedTextField(
                    target = passphraseTarget,
                    label = { Text("Passphrase") },
                    layoutType = KeyboardLayoutType.Qwerty,
                    masked = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
            }
            if (errorText != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorText,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onSignAndSend,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign & Send")
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

/**
 * Human-readable warning copy for an insufficient-balance result. The token
 * variant deliberately never mentions the network fee, because for token
 * accounts the fee is paid in the parent native coin and is not verified here.
 */
private fun insufficientBalanceMessage(symbol: String, result: BalanceSufficiency): String =
    when (result) {
        BalanceSufficiency.InsufficientTokenBalance ->
            "Insufficient $symbol balance to send this amount."
        BalanceSufficiency.InsufficientNative ->
            "Insufficient $symbol balance for the amount plus network fee."
        BalanceSufficiency.InsufficientNativePendingFee ->
            "Insufficient $symbol balance. The network fee is still estimating, so the required amount may be higher."
        BalanceSufficiency.Sufficient -> ""
    }

/**
 * Prominent, non-blocking warning shown when the balance can't cover the send.
 * It informs the user up front; the on-chain/provider send guards remain the
 * authoritative check, so the banner does not itself disable the Send button.
 */
@Composable
private fun InsufficientBalanceBanner(
    message: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FeatherIcons.AlertTriangle,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}


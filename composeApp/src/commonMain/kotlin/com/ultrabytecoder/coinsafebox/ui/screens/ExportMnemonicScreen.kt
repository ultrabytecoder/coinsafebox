package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod
import com.ultrabytecoder.coinsafebox.platform.preventScreenshots
import com.ultrabytecoder.coinsafebox.security.wipe
import com.ultrabytecoder.coinsafebox.ui.components.Numpad
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.AppKeyboard
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.SecureOutlinedTextField
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.LocalKeyboardController
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.SecureTargetAdapter
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.rememberKeyboardController
import com.ultrabytecoder.coinsafebox.ui.util.SecureScreen
import com.ultrabytecoder.coinsafebox.ui.util.SecureTextFieldState
import com.ultrabytecoder.coinsafebox.ui.viewmodel.ExportMnemonicViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportMnemonicScreen(
    navController: NavController,
    viewModel: ExportMnemonicViewModel
) {
    SecureScreen {
        ExportMnemonicScreenInner(navController, viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportMnemonicScreenInner(
    navController: NavController,
    viewModel: ExportMnemonicViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var mnemonicVisible by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val passwordField = remember { SecureTextFieldState() }
    val passwordTarget = remember {
        SecureTargetAdapter(
            state = passwordField,
            maxLength = 128,
            onValueChanged = { viewModel.onPasswordInput(it) }
        )
    }
    val submit: () -> Unit = { viewModel.submitPassword() }
    val controller = rememberKeyboardController(onAction = submit)

    remember {
        viewModel.onClipboardClear = { clipboardManager.setText(AnnotatedString("")) }
    }

    DisposableEffect(Unit) {
        onDispose { passwordField.wipe() }
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    CompositionLocalProvider(LocalKeyboardController provides controller) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recovery Phrase") },
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
                .preventScreenshots()
        ) {
            when (state) {
                is ExportMnemonicViewModel.State.AuthRequired -> {
                    val auth = state as ExportMnemonicViewModel.State.AuthRequired
                    val isPassword = auth.securityMethod == SecurityMethod.PASSWORD

                    // Password mode drives the on-screen keyboard; PIN mode uses the
                    // dedicated numpad wired directly to the ViewModel.
                    LaunchedEffect(isPassword) {
                        if (isPassword) controller.show(passwordTarget)
                    }
                    LaunchedEffect(auth.isLocked, auth.isProcessing) {
                        controller.setInputEnabled(!auth.isLocked && !auth.isProcessing)
                    }
                    DisposableEffect(Unit) {
                        onDispose { controller.hide() }
                    }

                    // Shake animation — triggers on each new shakeTriggerId
                    val shakeAnimatable = remember { Animatable(0f) }
                    LaunchedEffect(auth.shakeTriggerId) {
                        shakeAnimatable.animateTo(
                            targetValue = 0f,
                            animationSpec = keyframes {
                                durationMillis = 250
                                0f at 0
                                -12f at 50
                                12f at 100
                                -8f at 150
                                8f at 200
                                0f at 250
                            }
                        )
                    }
                    val shakeOffset = shakeAnimatable.value

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = if (isPassword) Arrangement.Center else Arrangement.SpaceBetween
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                if (isPassword) "Enter your password" else "Enter your PIN",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Verify your identity to view the recovery phrase",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(32.dp))

                            if (isPassword) {
                                SecureOutlinedTextField(
                                    target = passwordTarget,
                                    label = null,
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .offset { IntOffset(shakeOffset.toInt(), 0) },
                                    isError = auth.errorMessage != null,
                                    enabled = !auth.isLocked && !auth.isProcessing
                                )
                            } else {
                                PinDotsInline(
                                    enteredLength = auth.enteredPinLength,
                                    pinLength = auth.pinLength,
                                    modifier = Modifier.offset { IntOffset(shakeOffset.toInt(), 0) }
                                )
                            }

                            if (auth.isProcessing) {
                                Spacer(modifier = Modifier.height(16.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else if (auth.isLocked) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Too many attempts. Try again in ${auth.lockSecondsRemaining}s",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else if (auth.errorMessage != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    auth.errorMessage!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        if (isPassword) {
                            Spacer(modifier = Modifier.height(24.dp))
                            if (auth.isProcessing) {
                                Spacer(modifier = Modifier.height(16.dp))
                            } else {
                                Button(
                                    onClick = submit,
                                    enabled = !auth.isLocked && passwordField.text.isNotBlank(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                ) {
                                    Text("Verify", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            AppKeyboard()
                        } else {
                            Numpad(
                                onDigitClick = { viewModel.addDigit(('0'.code + it).toChar()) },
                                onDeleteClick = { viewModel.removeDigit() },
                                isLocked = auth.isLocked || auth.isProcessing
                            )
                        }
                    }
                }

                is ExportMnemonicViewModel.State.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is ExportMnemonicViewModel.State.Loaded -> {
                    val mnemonic = (state as ExportMnemonicViewModel.State.Loaded).mnemonic

                    // Wipe the mnemonic from memory when the screen leaves the composition
                    DisposableEffect(mnemonic) {
                        onDispose {
                            mnemonic.wipe()
                            viewModel.clearSensitiveData()
                        }
                    }
                    // Materialize the immutable String only while the phrase is actually
                    // displayed (NEW-7): it cannot be wiped, so it should not exist
                    // while the phrase is hidden.
                    val mnemonicText = if (mnemonicVisible) mnemonic.concatToString() else null

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Important",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Never share your recovery phrase. Anyone with these words can take your funds.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Recovery Phrase",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                IconButton(
                                    onClick = { mnemonicVisible = !mnemonicVisible },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (mnemonicVisible) FeatherIcons.EyeOff else FeatherIcons.Eye,
                                        contentDescription = if (mnemonicVisible) "Hide" else "Show",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = mnemonicText ?: "\u2022".repeat(mnemonic.size),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = if (mnemonicVisible) 10 else 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = {
                            mnemonicText?.let {
                                clipboardManager.setText(AnnotatedString(it))
                                copied = true
                                viewModel.scheduleClipboardClear()
                            }
                        },
                        enabled = mnemonicVisible,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (copied) "Copied!" else "Copy to clipboard")
                    }

                    Spacer(modifier = Modifier.weight(1f))
                }

                is ExportMnemonicViewModel.State.NotAvailable -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            (state as ExportMnemonicViewModel.State.NotAvailable).reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is ExportMnemonicViewModel.State.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (state as ExportMnemonicViewModel.State.Error).message,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
    }
}

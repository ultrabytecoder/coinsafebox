package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod
import com.ultrabytecoder.coinsafebox.security.wipe
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.AppKeyboard
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.SecureOutlinedTextField
import com.ultrabytecoder.coinsafebox.ui.keyboard.layouts.NumericNumpadLayout
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.LocalKeyboardController
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.SecureTargetAdapter
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.rememberKeyboardController
import com.ultrabytecoder.coinsafebox.ui.util.SecureTextFieldState
import com.ultrabytecoder.coinsafebox.ui.viewmodel.ChangePinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePinScreen(
    navController: NavController,
    viewModel: ChangePinViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val passwordField = remember { SecureTextFieldState() }
    var hasPassword by remember { mutableStateOf(false) }
    val passwordTarget = remember {
        SecureTargetAdapter(
            state = passwordField,
            maxLength = 128,
            onValueChanged = {
                hasPassword = it.isNotEmpty()
                viewModel.onPasswordInput(it)
            }
        )
    }
    val submit: () -> Unit = { viewModel.submitPasswordStage() }
    val controller = rememberKeyboardController(onAction = submit)

    val isPassword = state.securityMethod == SecurityMethod.PASSWORD
    val credentialLabel = if (isPassword) "password" else "PIN"

    LaunchedEffect(state.securityMethod) {
        // Password mode drives the on-screen QWERTY keyboard; PIN mode uses the
        // dedicated NumericNumpadLayout (wired to the ViewModel) instead.
        if (isPassword) {
            controller.show(passwordTarget)
        }
    }

    // Keep the keyboard inert while a submit is in flight (defense-in-depth).
    LaunchedEffect(state.isProcessing) {
        controller.setInputEnabled(!state.isProcessing)
    }

    DisposableEffect(Unit) {
        onDispose { passwordField.wipe() }
    }

    LaunchedEffect(state.stage) {
        passwordField.update("")
    }

    LaunchedEffect(state.errorMessage, state.isProcessing) {
        if (state.errorMessage != null || state.isProcessing) {
            passwordField.update("")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ChangePinViewModel.Event.NavigateBack -> navController.popBackStack()
            }
        }
    }

    CompositionLocalProvider(LocalKeyboardController provides controller) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Change $credentialLabel") },
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
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        when (state.stage) {
                            ChangePinViewModel.Stage.OldPin -> "Enter your current $credentialLabel"
                            ChangePinViewModel.Stage.NewPin -> "Choose a new $credentialLabel"
                            ChangePinViewModel.Stage.ConfirmNewPin -> "Confirm your new $credentialLabel"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Your recovery phrases are re-encrypted with the new $credentialLabel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    if (state.securityMethod == null) {
                        // Method not loaded yet — avoid flashing the PIN dots for a
                        // password-method user before it switches (M13).
                        Spacer(modifier = Modifier.height(32.dp))
                    } else if (isPassword) {
                        SecureOutlinedTextField(
                            target = passwordTarget,
                            label = { Text("Password") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            isError = state.errorMessage != null,
                            enabled = !state.isProcessing
                        )
                    } else {
                        PinDotsInline(
                            enteredLength = state.enteredLength,
                            pinLength = state.pinLength
                        )
                    }

                    if (state.isProcessing) {
                        Spacer(modifier = Modifier.height(16.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else if (state.errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            state.errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isPassword) {
                        if (state.isProcessing) {
                            Spacer(modifier = Modifier.height(16.dp))
                        } else {
                            Button(
                                onClick = submit,
                                enabled = hasPassword,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                            ) {
                                Text("Continue", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        AppKeyboard()
                    } else {
                        NumericNumpadLayout(
                            onDigitClick = { viewModel.addDigit(('0'.code + it).toChar()) },
                            onDeleteClick = { viewModel.removeDigit() },
                            isLocked = state.isProcessing
                        )
                    }
                }
            }
        }
    }
}


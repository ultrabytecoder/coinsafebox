package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import compose.icons.feathericons.X
import com.ultrabytecoder.coinsafebox.navigation.Screen
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.AppKeyboard
import com.ultrabytecoder.coinsafebox.ui.keyboard.components.SecureOutlinedTextField
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.LocalKeyboardController
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.SecureTargetAdapter
import com.ultrabytecoder.coinsafebox.ui.keyboard.state.rememberKeyboardController
import com.ultrabytecoder.coinsafebox.ui.util.SecureTextFieldState
import com.ultrabytecoder.coinsafebox.ui.viewmodel.SetPasswordEvent
import com.ultrabytecoder.coinsafebox.ui.viewmodel.SetPasswordViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetPasswordScreen(
    navController: NavController,
    viewModel: SetPasswordViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val passwordField = remember { SecureTextFieldState() }
    val passwordTarget = remember {
        SecureTargetAdapter(
            state = passwordField,
            maxLength = 128,
            onValueChanged = { viewModel.onInput(it) }
        )
    }
    val submit: () -> Unit = {
        if (state.isConfirming) viewModel.onConfirm() else viewModel.onContinue()
    }
    val controller = rememberKeyboardController(onAction = submit)

    DisposableEffect(Unit) {
        onDispose { passwordField.wipe() }
    }

    val scrollState = rememberScrollState()

    LaunchedEffect(controller.isVisible) {
        if (controller.isVisible) {
            kotlinx.coroutines.delay(100)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    LaunchedEffect(state.isConfirming) {
        // Clear on entering and leaving confirm mode so the field and the
        // ViewModel buffer stay in sync (onContinue already resets the buffer),
        // and (re)show the keyboard. Collapsed from a separate LaunchedEffect(Unit)
        // show to avoid a redundant double-show on entry (M12).
        passwordField.update("")
        controller.show(passwordTarget)
    }

    // Keep the keyboard inert while a submit is in flight (defense-in-depth on
    // top of the ViewModel's own isProcessing guard).
    LaunchedEffect(state.isProcessing) {
        controller.setInputEnabled(!state.isProcessing)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                SetPasswordEvent.NavigateToCreateWallet -> {
                    navController.navigate(Screen.CreateWallet) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
        }
    }

    CompositionLocalProvider(LocalKeyboardController provides controller) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Security") },
                    navigationIcon = {
                        if (!state.isProcessing) {
                            IconButton(onClick = {
                                if (state.isConfirming) {
                                    viewModel.onBack()
                                } else {
                                    navController.popBackStack()
                                }
                            }) {
                                Icon(
                                    if (state.isConfirming) FeatherIcons.ArrowLeft else FeatherIcons.X,
                                    contentDescription = "Back"
                                )
                            }
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
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                    Text(
                        if (state.isConfirming) "Confirm your password" else "Create a password",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (state.isConfirming) "Re-enter the same password" else "At least 12 characters with 3 character classes",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    SecureOutlinedTextField(
                        target = passwordTarget,
                        label = { Text(if (state.isConfirming) "Confirm password" else "Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = state.errorMessage != null || state.validationError != null,
                        enabled = !state.isProcessing
                    )

                    if (state.validationError != null && !state.isConfirming) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            state.validationError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (state.errorMessage != null) {
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
                    if (state.isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Button(
                            onClick = submit,
                            enabled = !state.isConfirming || passwordField.text.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text(
                                if (state.isConfirming) "Set Password" else "Continue",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    AppKeyboard()
                }
            }
        }
    }
}


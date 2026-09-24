package com.ultrabytecoder.coinsafebox.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.first
import com.ultrabytecoder.coinsafebox.ui.viewmodel.CreateWalletViewModel

/**
 * Multi-step create-wallet flow:
 *
 * - Way A (generate new): SETUP -> [GESTURE (optional)] -> PASSPHRASE -> REVEAL.
 *   The final mnemonic is generated once, at REVEAL, from fresh system
 *   entropy mixed with the gesture digest (when enabled).
 * - Way B (restore existing): SETUP validates and persists directly.
 *
 * All wallet-creation work runs in the ViewModel's [androidx.lifecycle.viewModelScope],
 * which survives screen disposal, so a slow DB write can never be cancelled by
 * navigation and leave the secrets wiped without a wallet.
 */
@Composable
fun CreateWalletFlow(
    navController: NavHostController,
    onWalletCreated: (walletId: Long) -> Unit,
    viewModel: CreateWalletViewModel
) {
    val step by viewModel.step.collectAsState()

    // Event-driven navigation: the ViewModel emits the wallet id to a
    // replay-1 SharedFlow exactly once per successful creation; this
    // collector receives it instantly (no polling) and navigates exactly
    // once (the flow exits after the first event).
    LaunchedEffect(Unit) {
        viewModel.walletCreated.first().let { onWalletCreated(it) }
    }

    when (step) {
        CreateWalletViewModel.Step.SETUP -> CreateWalletSetupScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
            onNext = viewModel::proceedFromSetup
        )

        CreateWalletViewModel.Step.PASSPHRASE -> PassphraseScreen(
            viewModel = viewModel,
            onBack = viewModel::back,
            onNext = viewModel::proceedFromPassphrase
        )

        CreateWalletViewModel.Step.GESTURE -> GestureEntropyScreen(
            onBack = viewModel::back,
            onConfirm = { digest ->
                viewModel.storeGestureDigest(digest)
                viewModel.proceedFromGesture()
            }
        )

        CreateWalletViewModel.Step.REVEAL -> RevealMnemonicScreen(
            viewModel = viewModel,
            onBack = viewModel::back
        )
    }
}

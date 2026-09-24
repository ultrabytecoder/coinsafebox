package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.domain.repository.PinState
import com.ultrabytecoder.coinsafebox.domain.usecase.CheckPinStatusUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed class StartupState {
    data object Loading : StartupState()
    data object NeedsPinSetup : StartupState()
    data object NeedsPinUnlock : StartupState()
}

/**
 * Startup wizard state — determines which wizard step to show at app launch.
 *
 * The PIN is set up FIRST (before any wallet exists), so the wizard is binary:
 *   Step 1: [StartupState.NeedsPinSetup]  → PIN never set (fresh install) or key material corrupt
 *   Step 2: [StartupState.NeedsPinUnlock] → PIN set, session locked — unlock to proceed
 *
 * After the PIN is set (or the session unlocked), the app navigates to wallet creation
 * when no wallet exists yet — the DB is SQLCipher-encrypted and only readable once the
 * session is unlocked, so wallet existence is never probed from a locked state.
 */
class StartupViewModel(
    checkPinStatus: CheckPinStatusUseCase
) : ViewModel() {

    val state: StateFlow<StartupState> = checkPinStatus()
        .map { pinState -> determineWizardStep(pinState) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StartupState.Loading)

    /**
     * Pure function that maps [PinState] → wizard step.
     */
    private fun determineWizardStep(pinState: PinState): StartupState = when (pinState) {
        PinState.Loading -> StartupState.Loading
        PinState.NotSetup -> StartupState.NeedsPinSetup
        is PinState.Setup -> {
            if (pinState.isCorrupted) {
                StartupState.NeedsPinSetup
            } else {
                StartupState.NeedsPinUnlock
            }
        }
    }
}
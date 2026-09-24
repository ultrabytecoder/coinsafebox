package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.data.ExistingKeyMaterialException
import com.ultrabytecoder.coinsafebox.data.SettingsKeys
import com.ultrabytecoder.coinsafebox.data.SettingsStore
import com.ultrabytecoder.coinsafebox.domain.repository.PinConfig
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod
import com.ultrabytecoder.coinsafebox.domain.usecase.SetupPinUseCase
import com.ultrabytecoder.coinsafebox.security.wipe
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SetupPinState(
    val isChoosingLength: Boolean = true,
    val pinLength: Int = PinConfig.PIN_LENGTH,
    val enteredPinLength: Int = 0,
    val isConfirming: Boolean = false,
    val isProcessing: Boolean = false,
    val errorMessage: String? = null,
    val isLocked: Boolean = false,
    val lockSecondsRemaining: Int = 0,
    /**
     * True after the user explicitly acknowledged recovery. While set, the
     * PIN confirmed through the normal numpad flow is submitted with
     * recoveryAcknowledged = true, so the existing key material is destroyed
     * by design instead of being refused again.
     */
    val recoveryMode: Boolean = false
)

sealed class SetupPinEvent {
    data object NavigateToCreateWallet : SetupPinEvent()

    /**
     * Existing key material was found while the lockout metadata was missing —
     * a fresh PIN would destroy the wallet. The UI must confirm recovery with
     * the user, then call [SetupPinViewModel.enterRecoveryMode] so the
     * re-entered PIN is submitted with recoveryAcknowledged = true.
     */
    data object RecoveryConfirmationRequired : SetupPinEvent()
}

/**
 * PIN setup runs FIRST in the startup wizard — before any wallet exists. After the PIN
 * is set, the session is open and the app always proceeds to wallet creation.
 *
 * The user first chooses the PIN length (6 or 8 digits); the choice is persisted in
 * [SettingsStorage] and used by every other PIN screen (unlock, change, export).
 *
 * The PIN is accumulated in a wipe-able [CharArray] buffer instead of immutable
 * Strings, so intermediate values never linger on the heap.
 */
class SetupPinViewModel(
    private val setupPinUseCase: SetupPinUseCase,
    private val settingsStorage: SettingsStore
) : ViewModel() {

    private val _state = MutableStateFlow(SetupPinState())
    val state: StateFlow<SetupPinState> = _state.asStateFlow()

    // First-entry PIN, kept as a private wipe-able buffer — never exposed in UI state.
    // Allocated lazily once the user picks a length.
    private var firstPin: CharArray? = null
    private var buffer: CharArray? = null
    private var bufferLength = 0

    private val _events = MutableSharedFlow<SetupPinEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: Flow<SetupPinEvent> = _events

    fun selectPinLength(length: Int) {
        if (length !in PinConfig.PIN_LENGTH_OPTIONS) return
        val s = _state.value
        if (s.isProcessing) return
        settingsStorage.putString(SettingsKeys.PIN_LENGTH, length.toString())
        firstPin = CharArray(length)
        buffer = CharArray(length)
        bufferLength = 0
        _state.update {
            it.copy(
                isChoosingLength = false,
                pinLength = length,
                enteredPinLength = 0,
                isConfirming = false,
                errorMessage = null
            )
        }
    }

    fun addDigit(digit: Char) {
        val s = _state.value
        if (s.isChoosingLength || s.isProcessing) return
        val buf = buffer ?: return
        if (bufferLength >= s.pinLength) return

        buf[bufferLength++] = digit
        _state.update { it.copy(enteredPinLength = bufferLength, errorMessage = null) }

        if (bufferLength == s.pinLength) {
            if (s.isConfirming) {
                confirmPin()
            } else {
                firstPin?.let { buf.copyInto(it) }
                buf.wipe()
                bufferLength = 0
                _state.update {
                    it.copy(
                        isConfirming = true,
                        enteredPinLength = 0,
                        errorMessage = null
                    )
                }
            }
        }
    }

    fun removeDigit() {
        val s = _state.value
        if (s.isChoosingLength || bufferLength == 0) return
        val buf = buffer ?: return
        buf[--bufferLength] = '\u0000'
        _state.update { it.copy(enteredPinLength = bufferLength) }
    }

    private fun confirmPin() {
        val buf = buffer ?: return
        val first = firstPin ?: return
        val matches = buf.contentEquals(first)
        buf.wipe()
        bufferLength = 0
        if (!matches) {
            first.wipe()
            _state.update {
                it.copy(
                    isConfirming = false,
                    enteredPinLength = 0,
                    errorMessage = "PINs do not match. Try again."
                )
            }
            return
        }

        // firstPin keeps its (wiped) reference so the normal re-entry flow
        // after a refusal can copy into it again — only onCleared() releases it.
        val pinChars = first.copyOf()
        first.wipe()
        _state.update { it.copy(isProcessing = true, enteredPinLength = 0) }
        viewModelScope.launch {
            try {
                // recoveryMode is latched by enterRecoveryMode() after the user
                // acknowledged the recovery dialog, so a re-entered PIN is
                // submitted with recoveryAcknowledged = true and the existing
                // key material is destroyed by design.
                setupPinUseCase(pinChars, SecurityMethod.PIN, recoveryAcknowledged = _state.value.recoveryMode)
                _state.update { it.copy(isProcessing = false, recoveryMode = false) }
                _events.emit(SetupPinEvent.NavigateToCreateWallet)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ExistingKeyMaterialException) {
                // Key material exists but the lockout state was missing — do NOT
                // overwrite silently. Ask the UI to confirm recovery; after the
                // user acknowledges (enterRecoveryMode), the re-entered PIN is
                // submitted with recoveryAcknowledged = true. The PIN buffers
                // are wiped here; the user re-enters after confirming.
                _state.update {
                    it.copy(
                        isConfirming = false,
                        isProcessing = false,
                        enteredPinLength = 0,
                        errorMessage = e.message
                    )
                }
                _events.emit(SetupPinEvent.RecoveryConfirmationRequired)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isConfirming = false,
                        isProcessing = false,
                        enteredPinLength = 0,
                        errorMessage = e.message ?: "Failed to set PIN"
                    )
                }
            } finally {
                pinChars.wipe()
            }
        }
    }

    /**
     * Enters the acknowledged-recovery flow: the user has confirmed that the
     * existing key material may be destroyed. The next PIN confirmed through
     * the normal numpad flow is submitted with recoveryAcknowledged = true
     * (destroys existing key material by design). Buffers are wiped so the
     * user re-enters a fresh PIN.
     */
    fun enterRecoveryMode() {
        val s = _state.value
        if (s.isProcessing) return
        if (s.pinLength !in PinConfig.PIN_LENGTH_OPTIONS) return
        // Wipe and re-allocate fresh wipe-able buffers: the user re-enters a
        // brand-new PIN through the normal numpad flow (addDigit requires
        // non-null buffers).
        firstPin?.wipe()
        buffer?.wipe()
        firstPin = CharArray(s.pinLength)
        buffer = CharArray(s.pinLength)
        bufferLength = 0
        _state.update {
            it.copy(
                isConfirming = false,
                isProcessing = false,
                enteredPinLength = 0,
                errorMessage = null,
                recoveryMode = true
            )
        }
    }

    override fun onCleared() {
        buffer?.wipe()
        firstPin?.wipe()
        bufferLength = 0
        super.onCleared()
    }
}

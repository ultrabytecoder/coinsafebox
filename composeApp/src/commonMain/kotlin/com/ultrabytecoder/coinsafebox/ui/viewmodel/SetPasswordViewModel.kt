package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod
import com.ultrabytecoder.coinsafebox.domain.usecase.CredentialValidator
import com.ultrabytecoder.coinsafebox.domain.usecase.SetupPinUseCase
import com.ultrabytecoder.coinsafebox.security.wipe
import com.ultrabytecoder.coinsafebox.ui.util.SecureTextFieldState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetPasswordState(
    val isConfirming: Boolean = false,
    val isProcessing: Boolean = false,
    val errorMessage: String? = null,
    val validationError: String? = null,
    val isLocked: Boolean = false,
    val lockSecondsRemaining: Int = 0
)

sealed class SetPasswordEvent {
    data object NavigateToCreateWallet : SetPasswordEvent()
}

class SetPasswordViewModel(
    private val setupPinUseCase: SetupPinUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(SetPasswordState())
    val state: StateFlow<SetPasswordState> = _state.asStateFlow()

    private val firstPassword = SecureTextFieldState()
    private val buffer = SecureTextFieldState()

    private val _events = MutableSharedFlow<SetPasswordEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: Flow<SetPasswordEvent> = _events

    fun onInput(text: String) {
        val s = _state.value
        if (s.isProcessing) return
        buffer.update(text)
        val trimmed = buffer.trimmedCopy()
        val validation = if (trimmed.isNotEmpty()) {
            CredentialValidator.validate(SecurityMethod.PASSWORD, trimmed).error
        } else {
            null
        }
        _state.update { it.copy(validationError = validation, errorMessage = null) }
    }

    fun onContinue() {
        val s = _state.value
        if (s.isProcessing) return
        val trimmed = buffer.trimmedCopy()
        if (trimmed.isEmpty()) return
        val validation = CredentialValidator.validate(SecurityMethod.PASSWORD, trimmed)
        if (!validation.ok) {
            _state.update { it.copy(validationError = validation.error, errorMessage = null) }
            return
        }
        buffer.update("")
        firstPassword.update(trimmed)
        trimmed.wipe()
        _state.update {
            it.copy(
                isConfirming = true,
                validationError = null,
                errorMessage = null
            )
        }
    }

    fun onConfirm() {
        val s = _state.value
        if (s.isProcessing) return
        val trimmed = buffer.trimmedCopy()
        if (trimmed.isEmpty()) return
        val matches = trimmed.contentEquals(firstPassword.toCharArray())
        buffer.update("")
        if (!matches) {
            firstPassword.wipe()
            _state.update {
                it.copy(
                    isConfirming = false,
                    errorMessage = "Passwords do not match. Try again."
                )
            }
            return
        }

        val passwordChars = firstPassword.toCharArray()
        firstPassword.wipe()
        _state.update { it.copy(isProcessing = true) }
        viewModelScope.launch {
            try {
                setupPinUseCase(passwordChars, SecurityMethod.PASSWORD)
                _state.update { it.copy(isProcessing = false) }
                _events.emit(SetPasswordEvent.NavigateToCreateWallet)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isConfirming = false,
                        isProcessing = false,
                        errorMessage = e.message ?: "Failed to set password"
                    )
                }
            } finally {
                passwordChars.wipe()
            }
        }
    }

    fun onBack() {
        val s = _state.value
        if (s.isProcessing) return
        if (s.isConfirming) {
            firstPassword.wipe()
            _state.update {
                it.copy(
                    isConfirming = false,
                    validationError = null,
                    errorMessage = null
                )
            }
        }
    }

    override fun onCleared() {
        buffer.wipe()
        firstPassword.wipe()
        super.onCleared()
    }
}

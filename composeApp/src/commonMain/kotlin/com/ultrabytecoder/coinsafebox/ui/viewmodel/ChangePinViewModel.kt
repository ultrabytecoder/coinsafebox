package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.data.SettingsKeys
import com.ultrabytecoder.coinsafebox.data.SettingsStorage
import com.ultrabytecoder.coinsafebox.domain.repository.ChangePinResult
import com.ultrabytecoder.coinsafebox.domain.repository.PinConfig
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod
import com.ultrabytecoder.coinsafebox.domain.usecase.ChangePinUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.CredentialValidator
import com.ultrabytecoder.coinsafebox.domain.usecase.GetSecurityMethodUseCase
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

class ChangePinViewModel(
    private val changePinUseCase: ChangePinUseCase,
    getSecurityMethod: GetSecurityMethodUseCase,
    settingsStorage: SettingsStorage
) : ViewModel() {

    enum class Stage { OldPin, NewPin, ConfirmNewPin }

    data class UiState(
        val stage: Stage = Stage.OldPin,
        val enteredLength: Int = 0,
        val pinLength: Int = PinConfig.PIN_LENGTH,
        val isProcessing: Boolean = false,
        val errorMessage: String? = null,
        val securityMethod: SecurityMethod? = null
    )

    sealed class Event {
        data object NavigateBack : Event()
    }

    private val pinLength = settingsStorage.getString(SettingsKeys.PIN_LENGTH)
        ?.toIntOrNull()
        ?.takeIf { it in PinConfig.PIN_LENGTH_OPTIONS }
        ?: PinConfig.PIN_LENGTH

    private val _state = MutableStateFlow(UiState(pinLength = pinLength))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: Flow<Event> = _events.asSharedFlow()

    // PIN buffers (wipe-able CharArray).
    private val oldPinBuffer = CharArray(pinLength)
    private val newPinBuffer = CharArray(pinLength)
    private val confirmBuffer = CharArray(pinLength)
    private var bufferLength = 0

    // Password buffers (wipe-able via SecureTextFieldState).
    private val oldPasswordBuffer = SecureTextFieldState()
    private val newPasswordBuffer = SecureTextFieldState()
    private val confirmPasswordBuffer = SecureTextFieldState()

    private var currentMethod: SecurityMethod = SecurityMethod.PIN

    init {
        viewModelScope.launch {
            getSecurityMethod().collect { method ->
                if (method != null) {
                    currentMethod = method
                    _state.update { it.copy(securityMethod = method) }
                }
            }
        }
    }

    fun addDigit(digit: Char) {
        val s = _state.value
        if (s.isProcessing) return
        if (bufferLength >= s.pinLength) return

        activeBuffer()[bufferLength++] = digit
        _state.update { it.copy(enteredLength = bufferLength, errorMessage = null) }

        if (bufferLength == s.pinLength) {
            advance()
        }
    }

    fun removeDigit() {
        val s = _state.value
        if (s.isProcessing || bufferLength == 0) return
        activeBuffer()[--bufferLength] = '\u0000'
        _state.update { it.copy(enteredLength = bufferLength) }
    }

    fun onPasswordInput(text: String) {
        val s = _state.value
        if (s.isProcessing) return
        val buffer = when (_state.value.stage) {
            Stage.OldPin -> oldPasswordBuffer
            Stage.NewPin -> newPasswordBuffer
            Stage.ConfirmNewPin -> confirmPasswordBuffer
        }
        buffer.update(text)
        _state.update { it.copy(errorMessage = null) }
    }

    fun submitPasswordStage() {
        val s = _state.value
        if (s.isProcessing) return
        val buffer = when (_state.value.stage) {
            Stage.OldPin -> oldPasswordBuffer
            Stage.NewPin -> newPasswordBuffer
            Stage.ConfirmNewPin -> confirmPasswordBuffer
        }
        val trimmed = buffer.trimmedCopy()
        if (trimmed.isEmpty()) return

        when (_state.value.stage) {
            Stage.OldPin -> {
                _state.update { it.copy(stage = Stage.NewPin, enteredLength = 0) }
            }
            Stage.NewPin -> {
                if (currentMethod == SecurityMethod.PASSWORD) {
                    val validation = CredentialValidator.validate(SecurityMethod.PASSWORD, trimmed)
                    if (!validation.ok) {
                        _state.update { it.copy(errorMessage = validation.error) }
                        return
                    }
                }
                _state.update { it.copy(stage = Stage.ConfirmNewPin, enteredLength = 0) }
            }
            Stage.ConfirmNewPin -> submitPassword()
        }
    }

    private fun activeBuffer(): CharArray = when (_state.value.stage) {
        Stage.OldPin -> oldPinBuffer
        Stage.NewPin -> newPinBuffer
        Stage.ConfirmNewPin -> confirmBuffer
    }

    private fun advance() {
        when (_state.value.stage) {
            Stage.OldPin -> {
                bufferLength = 0
                _state.update { it.copy(stage = Stage.NewPin, enteredLength = 0) }
            }
            Stage.NewPin -> {
                bufferLength = 0
                _state.update { it.copy(stage = Stage.ConfirmNewPin, enteredLength = 0) }
            }
            Stage.ConfirmNewPin -> submit()
        }
    }

    private fun submit() {
        val old = oldPinBuffer.copyOf()
        val fresh = newPinBuffer.copyOf()
        val confirm = confirmBuffer.copyOf()
        confirmBuffer.wipe()
        bufferLength = 0

        if (!fresh.contentEquals(confirm)) {
            old.wipe()
            fresh.wipe()
            confirm.wipe()
            _state.update {
                it.copy(
                    stage = Stage.NewPin,
                    enteredLength = 0,
                    errorMessage = "New PINs do not match. Try again."
                )
            }
            return
        }
        confirm.wipe()

        if (old.contentEquals(fresh)) {
            old.wipe()
            fresh.wipe()
            _state.update {
                it.copy(
                    stage = Stage.NewPin,
                    enteredLength = 0,
                    errorMessage = "New PIN must be different from the current PIN"
                )
            }
            return
        }

        _state.update { it.copy(isProcessing = true, enteredLength = 0) }
        viewModelScope.launch {
            try {
                when (val result = changePinUseCase(old, fresh, currentMethod)) {
                    ChangePinResult.Success -> _events.emit(Event.NavigateBack)
                    ChangePinResult.WrongOldPin -> resetAfterFailure(
                        "Current PIN is incorrect"
                    )
                    is ChangePinResult.Locked -> resetAfterFailure(
                        "Too many attempts. Try again later."
                    )
                    is ChangePinResult.Failed -> resetAfterFailure(result.message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                resetAfterFailure(e.message ?: "Failed to change PIN")
            } finally {
                old.wipe()
                fresh.wipe()
            }
        }
    }

    private fun submitPassword() {
        val old = oldPasswordBuffer.toCharArray()
        val fresh = newPasswordBuffer.toCharArray()
        val confirm = confirmPasswordBuffer.toCharArray()
        confirmPasswordBuffer.wipe()
        newPasswordBuffer.wipe()
        oldPasswordBuffer.wipe()

        if (!fresh.contentEquals(confirm)) {
            old.wipe()
            fresh.wipe()
            confirm.wipe()
            _state.update {
                it.copy(
                    stage = Stage.NewPin,
                    enteredLength = 0,
                    errorMessage = "New passwords do not match. Try again."
                )
            }
            return
        }
        confirm.wipe()

        if (old.contentEquals(fresh)) {
            old.wipe()
            fresh.wipe()
            _state.update {
                it.copy(
                    stage = Stage.NewPin,
                    enteredLength = 0,
                    errorMessage = "New password must be different from the current password"
                )
            }
            return
        }

        _state.update { it.copy(isProcessing = true, enteredLength = 0) }
        viewModelScope.launch {
            try {
                when (val result = changePinUseCase(old, fresh, currentMethod)) {
                    ChangePinResult.Success -> _events.emit(Event.NavigateBack)
                    ChangePinResult.WrongOldPin -> resetAfterFailure(
                        "Current password is incorrect"
                    )
                    is ChangePinResult.Locked -> resetAfterFailure(
                        "Too many attempts. Try again later."
                    )
                    is ChangePinResult.Failed -> resetAfterFailure(result.message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                resetAfterFailure(e.message ?: "Failed to change password")
            } finally {
                old.wipe()
                fresh.wipe()
            }
        }
    }

    /** Returns the UI to the start of the flow after a failed credential change. */
    private fun resetAfterFailure(message: String) {
        _state.update {
            it.copy(
                stage = Stage.OldPin,
                enteredLength = 0,
                isProcessing = false,
                errorMessage = message
            )
        }
    }

    override fun onCleared() {
        oldPinBuffer.wipe()
        newPinBuffer.wipe()
        confirmBuffer.wipe()
        oldPasswordBuffer.wipe()
        newPasswordBuffer.wipe()
        confirmPasswordBuffer.wipe()
        bufferLength = 0
        super.onCleared()
    }
}

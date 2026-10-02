package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.data.SettingsKeys
import com.ultrabytecoder.coinsafebox.data.SettingsStorage
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcController
import com.ultrabytecoder.coinsafebox.domain.repository.PinConfig
import com.ultrabytecoder.coinsafebox.domain.repository.PinState
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod
import com.ultrabytecoder.coinsafebox.domain.repository.VerifyResult
import com.ultrabytecoder.coinsafebox.domain.usecase.CheckPinStatusUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetSecurityMethodUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetWalletsUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SyncUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.VerifyPinUseCase
import com.ultrabytecoder.coinsafebox.providers.SyncMode
import com.ultrabytecoder.coinsafebox.security.wipe
import com.ultrabytecoder.coinsafebox.ui.util.SecureTextFieldState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class EnterPinState(
    val enteredPinLength: Int = 0,
    val pinLength: Int = PinConfig.PIN_LENGTH,
    val errorMessage: String? = null,
    val isLocked: Boolean = false,
    val lockSecondsRemaining: Int = 0,
    val lockedUntil: Long = 0L,
    val isProcessing: Boolean = false,
    val shakeTriggerId: Int = 0,
    val securityMethod: SecurityMethod? = null
)

sealed class EnterPinEvent {
    data class NavigateToAccountsList(val walletId: Long) : EnterPinEvent()
    data object NavigateToCreateWallet : EnterPinEvent()
    data object NavigateToRecovery : EnterPinEvent()
}

/**
 * The entered credential is accumulated in a wipe-able buffer instead of
 * immutable Strings, so intermediate values never linger on the heap.
 */
class EnterPinViewModel(
    private val verifyPinUseCase: VerifyPinUseCase,
    private val getWalletsUseCase: GetWalletsUseCase,
    private val syncUseCase: SyncUseCase,
    checkPinStatus: CheckPinStatusUseCase,
    getSecurityMethod: GetSecurityMethodUseCase,
    settingsStorage: SettingsStorage,
    private val wcController: WcController
) : ViewModel() {

    private val _state = MutableStateFlow(
        EnterPinState(
            pinLength = settingsStorage.getString(SettingsKeys.PIN_LENGTH)
                ?.toIntOrNull()
                ?.takeIf { it in PinConfig.PIN_LENGTH_OPTIONS }
                ?: PinConfig.PIN_LENGTH
        )
    )
    val state: StateFlow<EnterPinState> = _state.asStateFlow()

    private val buffer = CharArray(_state.value.pinLength)
    private var bufferLength = 0
    private val passwordBuffer = SecureTextFieldState()

    private val _events = MutableSharedFlow<EnterPinEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val events: Flow<EnterPinEvent> = _events

    init {
        viewModelScope.launch {
            getSecurityMethod().collect { method ->
                _state.update { it.copy(securityMethod = method) }
            }
        }

        viewModelScope.launch {
            checkPinStatus().collect { pinState ->
                when (pinState) {
                    is PinState.Setup -> {
                        if (pinState.isCorrupted) {
                            _events.tryEmit(EnterPinEvent.NavigateToRecovery)
                        } else {
                            val remaining = if (pinState.isLocked) {
                                ((pinState.lockedUntil - kotlin.time.Clock.System.now().toEpochMilliseconds()) / 1000)
                                    .coerceAtLeast(0).toInt()
                            } else {
                                0
                            }
                            _state.update {
                                it.copy(
                                    isLocked = pinState.isLocked,
                                    lockSecondsRemaining = remaining,
                                    lockedUntil = pinState.lockedUntil
                                )
                            }
                        }
                    }
                    else -> {
                        _state.update {
                            it.copy(
                                isLocked = false,
                                lockSecondsRemaining = 0
                            )
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                _state.update { s ->
                    if (s.isLocked && s.lockedUntil > 0L) {
                        val newRemaining = ((s.lockedUntil - kotlin.time.Clock.System.now().toEpochMilliseconds()) / 1000)
                            .coerceAtLeast(0).toInt()
                        s.copy(
                            lockSecondsRemaining = newRemaining,
                            isLocked = newRemaining > 0
                        )
                    } else {
                        s
                    }
                }
            }
        }
    }

    /** Routes to the main screen, or to wallet creation when no wallet exists yet. */
    private suspend fun navigateAfterUnlock() {
        // Reconnect the WalletConnect relay (dropped on lock) on its own
        // app-lifetime scope so it survives this screen being disposed.
        wcController.start()
        val walletId = getWalletsUseCase().first().firstOrNull()?.id
        if (walletId != null) {
            syncUseCase(viewModelScope, walletId, SyncMode.FULL)
            _events.emit(EnterPinEvent.NavigateToAccountsList(walletId))
        } else {
            _events.emit(EnterPinEvent.NavigateToCreateWallet)
        }
    }

    fun addDigit(digit: Char) {
        val s = _state.value
        if (s.isLocked) return
        if (s.isProcessing) return
        if (bufferLength >= s.pinLength) return

        buffer[bufferLength++] = digit
        _state.update { it.copy(enteredPinLength = bufferLength, errorMessage = null) }

        if (bufferLength == s.pinLength) {
            verifyPin()
        }
    }

    fun removeDigit() {
        if (bufferLength == 0) return
        buffer[--bufferLength] = '\u0000'
        _state.update { it.copy(enteredPinLength = bufferLength) }
    }

    fun onPasswordInput(text: String) {
        val s = _state.value
        if (s.isLocked) return
        if (s.isProcessing) return
        passwordBuffer.update(text)
        _state.update { it.copy(errorMessage = null) }
    }

    fun submitPassword() {
        val s = _state.value
        if (s.isLocked) return
        if (s.isProcessing) return
        val password = passwordBuffer.trimmedCopy()
        if (password.isEmpty()) return
        passwordBuffer.update("")
        _state.update { it.copy(isProcessing = true, enteredPinLength = 0) }
        viewModelScope.launch {
            try {
                when (val result = verifyPinUseCase(password)) {
                    is VerifyResult.Success -> {
                        _state.update { it.copy(isProcessing = false) }
                        navigateAfterUnlock()
                    }
                    is VerifyResult.WrongPin -> {
                        _state.update {
                            it.copy(
                                isProcessing = false,
                                enteredPinLength = 0,
                                shakeTriggerId = it.shakeTriggerId + 1,
                                errorMessage = "Incorrect password (${result.remainingAttempts} remaining)"
                            )
                        }
                    }
                    is VerifyResult.Locked -> {
                        val remaining = ((result.lockedUntil - kotlin.time.Clock.System.now().toEpochMilliseconds()) / 1000)
                            .coerceAtLeast(0).toInt()
                        _state.update {
                            it.copy(
                                isProcessing = false,
                                enteredPinLength = 0,
                                isLocked = true,
                                lockedUntil = result.lockedUntil,
                                lockSecondsRemaining = remaining,
                                errorMessage = null
                            )
                        }
                    }
                    is VerifyResult.Corrupted -> {
                        _state.update { it.copy(isProcessing = false) }
                        _events.tryEmit(EnterPinEvent.NavigateToRecovery)
                    }
                    is VerifyResult.SessionLocked -> {
                        _state.update {
                            it.copy(
                                isProcessing = false,
                                enteredPinLength = 0,
                                errorMessage = "Session locked, please try again"
                            )
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isProcessing = false,
                        enteredPinLength = 0,
                        errorMessage = e.message ?: "Verification failed"
                    )
                }
            } finally {
                password.wipe()
            }
        }
    }

    private fun verifyPin() {
        val pin = buffer.copyOf()
        buffer.wipe()
        bufferLength = 0
        _state.update { it.copy(isProcessing = true, enteredPinLength = 0) }
        viewModelScope.launch {
            try {
                when (val result = verifyPinUseCase(pin)) {
                    is VerifyResult.Success -> {
                        _state.update { it.copy(isProcessing = false) }
                        navigateAfterUnlock()
                    }
                    is VerifyResult.WrongPin -> {
                        _state.update {
                            it.copy(
                                isProcessing = false,
                                enteredPinLength = 0,
                                shakeTriggerId = it.shakeTriggerId + 1,
                                errorMessage = "Incorrect PIN (${result.remainingAttempts} remaining)"
                            )
                        }
                    }
                    is VerifyResult.Locked -> {
                        val remaining = ((result.lockedUntil - kotlin.time.Clock.System.now().toEpochMilliseconds()) / 1000)
                            .coerceAtLeast(0).toInt()
                        _state.update {
                            it.copy(
                                isProcessing = false,
                                enteredPinLength = 0,
                                isLocked = true,
                                lockedUntil = result.lockedUntil,
                                lockSecondsRemaining = remaining,
                                errorMessage = null
                            )
                        }
                    }
                    is VerifyResult.Corrupted -> {
                        _state.update { it.copy(isProcessing = false) }
                        _events.tryEmit(EnterPinEvent.NavigateToRecovery)
                    }
                    is VerifyResult.SessionLocked -> {
                        _state.update {
                            it.copy(
                                isProcessing = false,
                                enteredPinLength = 0,
                                errorMessage = "Session locked, please try again"
                            )
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isProcessing = false,
                        enteredPinLength = 0,
                        errorMessage = e.message ?: "Verification failed"
                    )
                }
            } finally {
                pin.wipe()
            }
        }
    }

    override fun onCleared() {
        buffer.wipe()
        passwordBuffer.wipe()
        bufferLength = 0
        super.onCleared()
    }
}

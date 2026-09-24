package com.ultrabytecoder.coinsafebox.domain.repository

import com.ultrabytecoder.coinsafebox.security.SecretCipher
import kotlinx.coroutines.flow.StateFlow

sealed interface PinState {
    data object Loading : PinState
    data object NotSetup : PinState
    data class Setup(
        val failedAttempts: Int,
        val lockedUntil: Long,
        val isCorrupted: Boolean = false
    ) : PinState {
        val isLocked: Boolean get() = kotlin.time.Clock.System.now().toEpochMilliseconds() < lockedUntil
    }
}

/**
 * Result of a PIN change attempt.
 */
sealed class ChangePinResult {
    data object Success : ChangePinResult()
    data object WrongOldPin : ChangePinResult()
    data class Locked(val lockedUntil: Long) : ChangePinResult()
    data class Failed(val message: String) : ChangePinResult()
}

interface PinRepository {
    val pinStateFlow: StateFlow<PinState>
    val securityMethodFlow: StateFlow<SecurityMethod?>

    /**
     * Sets up a fresh credential. [recoveryAcknowledged] must be true when the user
     * arrived through the corrupted-state recovery flow (existing key material will
     * be destroyed); with false and existing key material present, setup is refused
     * so a fresh PIN can never silently destroy a wallet.
     */
    suspend fun setupPin(pin: CharArray, method: SecurityMethod, recoveryAcknowledged: Boolean = false)
    suspend fun verifyPin(pin: CharArray): VerifyResult

    /**
     * Changes the credential: verifies [oldPin], re-wraps the DEK envelope with [newPin].
     * The mnemonic is wrapped by the device hardware key ([SecretCipher]), independent
     * of the PIN, so no re-encryption of stored secrets is needed on PIN change.
     * [newMethod] is the security method the new credential uses.
     */
    suspend fun changePin(oldPin: CharArray, newPin: CharArray, newMethod: SecurityMethod): ChangePinResult
}
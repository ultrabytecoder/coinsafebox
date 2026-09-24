package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.repository.ChangePinResult
import com.ultrabytecoder.coinsafebox.domain.repository.PinRepository
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod

class ChangePinUseCase(
    private val pinRepository: PinRepository
) {
    /**
     * Verifies [oldPin] and switches the DEK envelope to [newPin]. On failure the
     * old PIN remains fully functional. [newMethod] is the security method the
     * new credential uses.
     */
    suspend operator fun invoke(oldPin: CharArray, newPin: CharArray, newMethod: SecurityMethod): ChangePinResult =
        pinRepository.changePin(oldPin, newPin, newMethod)
}

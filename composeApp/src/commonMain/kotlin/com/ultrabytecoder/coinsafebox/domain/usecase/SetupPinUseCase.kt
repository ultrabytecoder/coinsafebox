package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.repository.PinRepository
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod

class SetupPinUseCase(
    private val pinRepository: PinRepository
) {
    suspend operator fun invoke(pin: CharArray, method: SecurityMethod, recoveryAcknowledged: Boolean = false) {
        pinRepository.setupPin(pin, method, recoveryAcknowledged)
    }
}

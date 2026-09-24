package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.repository.PinRepository
import com.ultrabytecoder.coinsafebox.domain.repository.VerifyResult

class VerifyPinUseCase(
    private val pinRepository: PinRepository
) {
    suspend operator fun invoke(pin: CharArray): VerifyResult = pinRepository.verifyPin(pin)
}
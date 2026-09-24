package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.repository.PinRepository
import com.ultrabytecoder.coinsafebox.domain.repository.PinState
import kotlinx.coroutines.flow.StateFlow

class CheckPinStatusUseCase(
    private val pinRepository: PinRepository
) {
    operator fun invoke(): StateFlow<PinState> = pinRepository.pinStateFlow
}
package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.repository.PinRepository
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod
import kotlinx.coroutines.flow.StateFlow

class GetSecurityMethodUseCase(
    private val pinRepository: PinRepository
) {
    operator fun invoke(): StateFlow<SecurityMethod?> = pinRepository.securityMethodFlow
}

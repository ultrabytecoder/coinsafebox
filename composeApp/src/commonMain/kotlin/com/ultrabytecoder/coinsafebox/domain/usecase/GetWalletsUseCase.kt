package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.model.WalletInfo
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import kotlinx.coroutines.flow.Flow

class GetWalletsUseCase(
    private val walletRepository: WalletRepository
) {
    operator fun invoke(): Flow<List<WalletInfo>> =
        walletRepository.getWalletsFlow()
}

package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository

class RenameWalletUseCase(
    private val walletRepository: WalletRepository
) {
    suspend operator fun invoke(id: Long, name: String) {
        walletRepository.renameWallet(id, name)
    }
}

package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow

class GetAccountsUseCase(
    private val accountRepository: AccountRepository
) {
    fun byWallet(walletId: Long): Flow<List<AccountInfo>> =
        accountRepository.getAccountsByWalletFlow(walletId)

    suspend fun byId(id: String): AccountInfo? =
        accountRepository.getAccount(id)
}

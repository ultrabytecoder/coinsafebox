package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import kotlinx.coroutines.flow.first

class DeleteWalletUseCase(
    private val walletRepository: WalletRepository,
    private val accountRepository: AccountRepository,
    private val utxoRepository: UtxoRepository,
    private val transactionRepository: TransactionRepository
) {
    suspend operator fun invoke(walletId: Long) {
        val accounts = accountRepository.getAccountsByWalletFlow(walletId).first()
        for (account in accounts) {
            utxoRepository.deleteUtxosByAccount(account.id)
            transactionRepository.deleteByAccount(account.id)
        }
        accountRepository.deleteAccountsByWallet(walletId)
        walletRepository.deleteWallet(walletId)
    }
}

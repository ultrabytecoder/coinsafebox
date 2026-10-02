package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import kotlinx.coroutines.flow.first

class DeleteAccountUseCase(
    private val accountRepository: AccountRepository,
    private val utxoRepository: UtxoRepository,
    private val transactionRepository: TransactionRepository
) {
    /**
     * Removes the parent account [accountId] together with all of its child
     * token accounts, each token's utxos + transactions, the parent's own
     * utxos + transactions, and finally the parent row itself (which cascades
     * to its token rows via AccountRepository.deleteAccount).
     */
    suspend operator fun invoke(accountId: String) {
        val tokens = accountRepository.getTokensByParentFlow(accountId).first()
        for (token in tokens) {
            utxoRepository.deleteUtxosByAccount(token.id)
            transactionRepository.deleteByAccount(token.id)
        }
        utxoRepository.deleteUtxosByAccount(accountId)
        transactionRepository.deleteByAccount(accountId)
        accountRepository.deleteAccount(accountId)
    }
}

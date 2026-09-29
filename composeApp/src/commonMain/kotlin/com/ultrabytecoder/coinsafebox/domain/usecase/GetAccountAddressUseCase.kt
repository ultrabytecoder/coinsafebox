package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import com.ultrabytecoder.coinsafebox.providers.ProviderFactory

class GetAccountAddressUseCase(
    private val accountRepository: AccountRepository,
    private val utxoRepository: UtxoRepository,
    private val transactionRepository: TransactionRepository,
    private val keyProvider: KeyProvider,
    private val networkConfig: NetworkConfig,
    private val walletRepository: WalletRepository
) {
    suspend operator fun invoke(accountId: String): String {
        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        val wallet = walletRepository.getWallet(account.walletId)
            ?: throw IllegalArgumentException("Wallet not found: ${account.walletId}")

        return if (wallet.isReadOnly) {
            val xpub = if (account.type is AccountType.Btc) accountRepository.getXpub(accountId) else null
            val provider = ProviderFactory.createReadOnly(
                account.type, xpub, utxoRepository, accountRepository,
                transactionRepository, networkConfig, account.params
            )
            provider.getAddress(accountId)
        } else {
            keyProvider.withMasterSeed(account.walletId) { masterSeed ->
                val provider = ProviderFactory.create(
                    account.type, masterSeed, utxoRepository, accountRepository,
                    transactionRepository, networkConfig, account.params
                )
                provider.getAddress(accountId)
            }
        }
    }
}

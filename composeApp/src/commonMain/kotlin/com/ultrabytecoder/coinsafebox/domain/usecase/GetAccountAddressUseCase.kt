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

        // Fast path: address is persisted (post persist-at-creation).
        account.address?.takeIf { it.isNotBlank() }?.let { return it }

        // Fallback: derive on the fly (migration path for pre-persist accounts).
        val wallet = walletRepository.getWallet(account.walletId)
            ?: throw IllegalArgumentException("Wallet not found: ${account.walletId}")

        return if (wallet.isReadOnly) {
            // Read-only with null address is an unrecoverable stuck state.
            throw IllegalStateException(
                "Read-only account ${account.id} has no persisted address. " +
                    "Restore this wallet from its mnemonic to recover."
            )
        } else {
            keyProvider.withMasterSeed(account.walletId) { masterSeed ->
                val provider = ProviderFactory.create(
                    account.type, masterSeed, utxoRepository, accountRepository,
                    transactionRepository, networkConfig, account.params
                )
                val address = provider.getAddress(accountId)
                // Self-heal: persist the derived address so subsequent reads
                // hit the fast path.
                accountRepository.updateAddress(accountId, address)
                address
            }
        }
    }
}

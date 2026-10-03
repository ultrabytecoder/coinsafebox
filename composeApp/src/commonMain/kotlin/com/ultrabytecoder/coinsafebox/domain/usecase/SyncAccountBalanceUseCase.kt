package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import com.ultrabytecoder.coinsafebox.providers.ProviderFactory
import com.ultrabytecoder.coinsafebox.providers.SyncMode
import kotlinx.coroutines.CancellationException

/**
 * Single-account balance sync (suspending). Used by the Send flow to refresh the
 * sender's balance after a transaction. Does NOT touch the transactions table.
 */
class SyncAccountBalanceUseCase(
    private val accountRepository: AccountRepository,
    private val utxoRepository: UtxoRepository,
    private val transactionRepository: TransactionRepository,
    private val keyProvider: KeyProvider,
    private val networkConfig: NetworkConfig,
    private val syncManager: SyncManager,
    private val walletRepository: WalletRepository
) {
    suspend operator fun invoke(accountId: String, syncMode: SyncMode = SyncMode.NORMAL) {
        if (!syncManager.tryAcquireBalance(accountId)) {
            println("Balance sync already in progress for $accountId, skipping")
            return
        }
        try {
            val account = accountRepository.getAccount(accountId)
                ?: throw IllegalArgumentException("Account not found: $accountId")
            val wallet = walletRepository.getWallet(account.walletId)
                ?: throw IllegalArgumentException("Wallet not found: ${account.walletId}")
            if (wallet.isReadOnly) {
                val xpub = if (account.type is AccountType.Btc) accountRepository.getXpub(accountId) else null
                val provider = ProviderFactory.createReadOnly(
                    account.type, xpub, utxoRepository, accountRepository,
                    transactionRepository, networkConfig, account.params
                )
                provider.syncBalance(accountId, syncMode)
            } else {
                keyProvider.withMasterSeed(account.walletId) { masterSeed ->
                    val provider = ProviderFactory.create(
                        account.type, masterSeed, utxoRepository, accountRepository,
                        transactionRepository, networkConfig, account.params
                    )
                    provider.syncBalance(accountId, syncMode)
                }
            }
        } catch (e: CancellationException) {
            // Propagate cancellation — never treat a cancelled sync as a failed one.
            throw e
        } catch (e: Exception) {
            println("Balance sync failed for account $accountId: ${e.message}")
        } finally {
            syncManager.releaseBalance(accountId)
        }
    }
}

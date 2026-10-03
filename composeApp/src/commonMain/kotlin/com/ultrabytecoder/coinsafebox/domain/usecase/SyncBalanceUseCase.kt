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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Wallet-wide balance sync: fan out one balance sync per account of the wallet.
 * Fire-and-forget; per-account errors are swallowed. Balance sync does NOT touch
 * the transactions table (that is the responsibility of [SyncTransactionsUseCase]).
 */
class SyncBalanceUseCase(
    private val accountRepository: AccountRepository,
    private val utxoRepository: UtxoRepository,
    private val transactionRepository: TransactionRepository,
    private val keyProvider: KeyProvider,
    private val networkConfig: NetworkConfig,
    private val syncManager: SyncManager,
    private val walletRepository: WalletRepository
) {
    operator fun invoke(scope: CoroutineScope, walletId: Long, syncMode: SyncMode = SyncMode.NORMAL) {
        scope.launch {
            println("Start balance-syncing accounts of wallet $walletId (mode=$syncMode)")

            val accounts = accountRepository.getAccountsByWalletFlow(walletId).first()

            for (account in accounts) {
                launch {
                    if (!syncManager.tryAcquireBalance(account.id)) {
                        println("Balance sync already in progress for ${account.id}, skipping")
                        return@launch
                    }
                    try {
                        val wallet = walletRepository.getWallet(account.walletId) ?: return@launch
                        if (wallet.isReadOnly) {
                            val xpub = if (account.type is AccountType.Btc) accountRepository.getXpub(account.id) else null
                            val provider = ProviderFactory.createReadOnly(account.type, xpub, utxoRepository, accountRepository, transactionRepository, networkConfig, account.params)
                            provider.syncBalance(account.id, syncMode)
                        } else {
                            keyProvider.withMasterSeed(account.walletId) { masterSeed ->
                                val provider = ProviderFactory.create(account.type, masterSeed, utxoRepository, accountRepository, transactionRepository, networkConfig, account.params)
                                provider.syncBalance(account.id, syncMode)
                            }
                        }
                    } catch (e: CancellationException) {
                        // Propagate cancellation — never treat a cancelled sync as a
                        // failed one (which would surface a misleading error).
                        throw e
                    } catch (e: Exception) {
                        println("Balance sync failed for account ${account.id}: ${e.message}")
                    } finally {
                        syncManager.releaseBalance(account.id)
                    }
                }
            }
        }
    }
}

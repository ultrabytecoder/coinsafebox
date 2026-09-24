package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import com.ultrabytecoder.coinsafebox.providers.ProviderFactory
import com.ultrabytecoder.coinsafebox.providers.SyncMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SyncUseCase(
    private val accountRepository: AccountRepository,
    private val utxoRepository: UtxoRepository,
    private val transactionRepository: TransactionRepository,
    private val keyProvider: KeyProvider,
    private val networkConfig: NetworkConfig,
    private val syncManager: SyncManager
) {
    operator fun invoke(scope: CoroutineScope, walletId: Long, syncMode: SyncMode = SyncMode.NORMAL) {
        scope.launch {
            println("Start syncing accounts of wallet $walletId (mode=$syncMode)")

            val accounts = accountRepository.getAccountsByWalletFlow(walletId).first()

            for (account in accounts) {
                launch {
                    if (!syncManager.tryAcquire(account.id)) {
                        println("Sync already in progress for ${account.id}, skipping")
                        return@launch
                    }
                    try {
                        keyProvider.withMasterSeed(account.walletId) { masterSeed ->
                            val provider = ProviderFactory.create(account.type, masterSeed, utxoRepository, accountRepository, transactionRepository, networkConfig, account.params)
                            provider.sync(account.id, syncMode)
                        }
                    } catch (e: CancellationException) {
                        // Propagate cancellation — never treat a cancelled sync as a
                        // failed one (which would surface a misleading error).
                        throw e
                    } catch (e: Exception) {
                        println("Sync failed for account ${account.id}: ${e.message}")
                    } finally {
                        syncManager.release(account.id)
                    }
                }
            }
        }
    }
}

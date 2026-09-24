package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.FeeEstimation
import com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import com.ultrabytecoder.coinsafebox.providers.ProviderFactory
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EstimateFeeUseCase(
    private val accountRepository: AccountRepository,
    private val utxoRepository: UtxoRepository,
    private val transactionRepository: TransactionRepository,
    private val keyProvider: KeyProvider,
    private val networkConfig: NetworkConfig
) {
    // Simple in-memory cache to prevent API spam when user is typing amounts
    private var cachedEstimation: FeeEstimation? = null
    private var cacheKey: String? = null
    private var cacheTimestamp: Long = 0L
    private val cacheTtl = 30_000L // 30 seconds
    // Guards the shared cache fields so concurrent invocations never observe a
    // torn (partially-updated) cache state. The slow estimateFee() call runs
    // OUTSIDE the lock, so caching does not serialize fee estimation.
    private val cacheMutex = Mutex()

    suspend operator fun invoke(
        accountId: String,
        amount: BigDecimal,
        recipientAddress: String? = null,
        feeParams: CustomFeeParams? = null
    ): FeeEstimation {
        val key = "$accountId|$amount|$recipientAddress|$feeParams"
        val now = Clock.System.now().toEpochMilliseconds()
        
        // Return cached result if key matches and within TTL. The read is done
        // under the lock so concurrent invocations never see a torn cache state.
        val cached = cacheMutex.withLock {
            if (key == cacheKey && now - cacheTimestamp < cacheTtl) cachedEstimation else null
        }
        if (cached != null) return cached

        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")

        val estimation = keyProvider.withMasterSeed(account.walletId) { masterSeed ->
            val provider = ProviderFactory.create(
                account.type, masterSeed, utxoRepository, accountRepository,
                transactionRepository, networkConfig, account.params
            )
            provider.estimateFee(account.id, amount, recipientAddress, feeParams)
        }
        
        // Update cache with new result. The write is done under the lock so the
        // three cache fields are committed atomically.
        cacheMutex.withLock {
            cachedEstimation = estimation
            cacheKey = key
            cacheTimestamp = now
        }
        
        return estimation
    }
}

package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import com.ultrabytecoder.coinsafebox.providers.ProviderFactory

class SendUseCase(
    private val accountRepository: AccountRepository,
    private val utxoRepository: UtxoRepository,
    private val transactionRepository: TransactionRepository,
    private val keyProvider: KeyProvider,
    private val networkConfig: NetworkConfig
) {
    suspend operator fun invoke(
        accountId: String,
        address: String,
        amount: BigDecimal,
        feeParams: CustomFeeParams? = null
    ): String {
        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")

        return keyProvider.withMasterSeed(account.walletId) { masterSeed ->
            val provider = ProviderFactory.create(
                account.type, masterSeed, utxoRepository, accountRepository,
                transactionRepository, networkConfig, account.params
            )
            val rawTx = provider.createTransaction(address, amount, account.id, feeParams)
            provider.broadcast(rawTx)
        }
    }
}

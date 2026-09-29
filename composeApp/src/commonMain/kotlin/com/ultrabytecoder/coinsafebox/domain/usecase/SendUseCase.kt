package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.exception.WrongMnemonicException
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import com.ultrabytecoder.coinsafebox.providers.Provider
import com.ultrabytecoder.coinsafebox.providers.ProviderFactory

class SendUseCase(
    private val accountRepository: AccountRepository,
    private val utxoRepository: UtxoRepository,
    private val transactionRepository: TransactionRepository,
    private val keyProvider: KeyProvider,
    private val networkConfig: NetworkConfig,
    private val walletRepository: WalletRepository
) {
    suspend operator fun invoke(
        accountId: String,
        address: String,
        amount: BigDecimal,
        feeParams: CustomFeeParams? = null,
        mnemonic: CharArray? = null,
        passphrase: CharArray = CharArray(0)
    ): String {
        val account = accountRepository.getAccount(accountId)
            ?: throw IllegalArgumentException("Account not found: $accountId")
        val wallet = walletRepository.getWallet(account.walletId)
            ?: throw IllegalArgumentException("Wallet not found: ${account.walletId}")

        return if (wallet.isReadOnly) {
            if (mnemonic == null) throw IllegalArgumentException("Recovery phrase is required to send from a read-only wallet")
            if (wallet.hasPassphrase && passphrase.isEmpty()) throw IllegalArgumentException("Passphrase is required")
            sendReadOnly(account, address, amount, feeParams, mnemonic, passphrase)
        } else {
            sendFull(account, address, amount, feeParams)
        }
    }

    private suspend fun sendFull(
        account: AccountInfo,
        address: String,
        amount: BigDecimal,
        feeParams: CustomFeeParams?
    ): String = keyProvider.withMasterSeed(account.walletId) { masterSeed ->
        val provider = ProviderFactory.create(
            account.type, masterSeed, utxoRepository, accountRepository,
            transactionRepository, networkConfig, account.params
        )
        val rawTx = provider.createTransaction(address, amount, account.id, feeParams)
        provider.broadcast(rawTx)
    }

    private suspend fun sendReadOnly(
        account: AccountInfo,
        address: String,
        amount: BigDecimal,
        feeParams: CustomFeeParams?,
        mnemonic: CharArray,
        passphrase: CharArray
    ): String = keyProvider.withTransientSeed(mnemonic, passphrase) { seed ->
        val provider = ProviderFactory.create(
            account.type, seed, utxoRepository, accountRepository,
            transactionRepository, networkConfig, account.params
        )
        verifyMnemonicMatches(account, provider)
        val rawTx = provider.createTransaction(address, amount, account.id, feeParams)
        provider.broadcast(rawTx)
    }

    private suspend fun verifyMnemonicMatches(account: AccountInfo, provider: Provider) {
        val expected = if (account.type.isToken) {
            accountRepository.getAccount(account.parentAccountId ?: "")?.address
        } else {
            account.address
        }
        require(!expected.isNullOrEmpty()) { "Stored address is missing; cannot verify recovery phrase" }
        val derived = provider.getAddress(account.id)
        if (!derived.equals(expected, ignoreCase = true)) throw WrongMnemonicException()
    }
}

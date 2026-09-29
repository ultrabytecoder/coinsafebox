package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import com.ultrabytecoder.coinsafebox.providers.BtcXpub
import com.ultrabytecoder.coinsafebox.providers.ProviderFactory
import fr.acinq.bitcoin.DeterministicWallet
import kotlinx.coroutines.flow.first

class RemoveMasterKeyUseCase(
    private val walletRepository: WalletRepository,
    private val accountRepository: AccountRepository,
    private val keyProvider: KeyProvider,
    private val utxoRepository: UtxoRepository,
    private val transactionRepository: TransactionRepository,
    private val networkConfig: NetworkConfig
) {
    suspend operator fun invoke(walletId: Long) {
        val wallet = walletRepository.getWallet(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")
        require(!wallet.isReadOnly) { "Wallet is already read-only" }
        val accounts = accountRepository.getAccountsByWalletFlow(walletId).first()
        for (account in accounts) {
            keyProvider.withMasterSeed(walletId) { seed ->
                val provider = ProviderFactory.create(
                    account.type, seed, utxoRepository, accountRepository,
                    transactionRepository, networkConfig, account.params
                )
                val address = provider.getAddress(account.id)
                accountRepository.updateAddress(account.id, address)
                if (account.type is AccountType.Btc && accountRepository.getXpub(account.id) == null) {
                    val masterKey = DeterministicWallet.generate(seed)
                    val xpub = BtcXpub.fromMasterKey(masterKey, account.derivationPath, networkConfig.btcBip84CoinType == 1L)
                    accountRepository.updateXpub(account.id, xpub)
                }
            }
        }
        walletRepository.clearMasterKey(walletId)
    }
}

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

/**
 * One-shot startup pass that backfills addresses (and BTC xpubs) for any
 * native account row that is missing them. Closes the migration window for
 * accounts created before persist-at-creation was introduced, and self-heals
 * any future code path that forgets to persist.
 *
 * Safe to run on every unlock: it is a no-op when all addresses are present.
 */
class ReconcileAddressesUseCase(
    private val walletRepository: WalletRepository,
    private val accountRepository: AccountRepository,
    private val keyProvider: KeyProvider,
    private val utxoRepository: UtxoRepository,
    private val transactionRepository: TransactionRepository,
    private val networkConfig: NetworkConfig,
) {
    suspend operator fun invoke(walletId: Long) {
        val wallet = walletRepository.getWallet(walletId) ?: return
        if (wallet.isReadOnly) return

        val accounts = accountRepository.getAccountsByWalletFlow(walletId).first()
            .filter { it.type.isNative }

        val needsAddress = accounts.filter { it.address.isNullOrBlank() }
        val needsXpub = accounts.filter {
            it.type is AccountType.Btc && accountRepository.getXpub(it.id) == null
        }

        if (needsAddress.isEmpty() && needsXpub.isEmpty()) return

        keyProvider.withMasterSeed(walletId) { seed ->
            val masterKey = DeterministicWallet.generate(seed)

            needsAddress.forEach { account ->
                val provider = ProviderFactory.create(
                    account.type, seed, utxoRepository, accountRepository,
                    transactionRepository, networkConfig, account.params
                )
                val address = provider.getAddress(account.id)
                accountRepository.updateAddress(account.id, address)
            }

            needsXpub.forEach { account ->
                val xpub = BtcXpub.fromMasterKey(
                    masterKey, account.derivationPath, networkConfig.btcBip84CoinType == 1L
                )
                accountRepository.updateXpub(account.id, xpub)
            }
        }
    }
}

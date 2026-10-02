package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import com.ultrabytecoder.coinsafebox.providers.BtcXpub
import com.ultrabytecoder.coinsafebox.providers.DerivationPathResolver
import com.ultrabytecoder.coinsafebox.providers.ProviderFactory
import fr.acinq.bitcoin.DeterministicWallet
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CreateAccountUseCase(
    private val accountRepository: AccountRepository,
    private val networkConfig: NetworkConfig,
    private val keyProvider: KeyProvider,
    private val utxoRepository: UtxoRepository,
    private val transactionRepository: TransactionRepository,
) {
    /**
     * Creates a new native account and returns its ID.
     *
     * The address is derived and persisted at creation time (not on first
     * access) so that the read-only downgrade path can verify all addresses
     * are materialised before the master key is irreversibly removed.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(
        walletId: Long,
        displayName: String,
        type: AccountType,
        symbol: String,
        params: String? = null,
        derivationPath: String? = null
    ): String {
        require(type.isNative) { "Use AddTokenUseCase for tokens" }

        val maxIndex = accountRepository.getMaxAccountIndexByWalletAndAccountType(walletId, type.toDbCode())
        val accountIndex = (maxIndex ?: -1) + 1
        val accountNameIndex = accountIndex + 1

        val resolvedPath = derivationPath
            ?: DerivationPathResolver.defaultPath(type, accountIndex, networkConfig)

        require(DerivationPathResolver.isValidPath(resolvedPath, type, networkConfig)) {
            "Invalid derivation path for $type: $resolvedPath"
        }

        require(!accountRepository.existsByDerivationPath(walletId, resolvedPath)) {
            "An account with derivation path $resolvedPath already exists"
        }

        val id = Uuid.random().toString()
        val account = AccountInfo(
            id = id,
            walletId = walletId,
            name = "$displayName account $accountNameIndex",
            amount = "0",
            type = type,
            symbol = symbol,
            address = null,
            accountIndex = accountIndex,
            derivationPath = resolvedPath,
            params = params,
            parentAccountId = null
        )
        accountRepository.insertAccount(account)

        // Derive and persist the address immediately. The startup reconciler
        // (ReconcileAddressesUseCase) self-heals if a crash occurs between
        // the insert and the update, and RemoveMasterKeyUseCase verifies all
        // addresses are present before clearing the key.
        keyProvider.withMasterSeed(walletId) { seed ->
            if (type is AccountType.Btc) {
                val masterKey = DeterministicWallet.generate(seed)
                val xpub = BtcXpub.fromMasterKey(masterKey, resolvedPath, networkConfig.btcBip84CoinType == 1L)
                accountRepository.updateXpub(id, xpub)
            }
            val provider = ProviderFactory.create(
                type, seed, utxoRepository, accountRepository,
                transactionRepository, networkConfig, params
            )
            val address = provider.getAddress(id)
            accountRepository.updateAddress(id, address)
        }

        return id
    }
}

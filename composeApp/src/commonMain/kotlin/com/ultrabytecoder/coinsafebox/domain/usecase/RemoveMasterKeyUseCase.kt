package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import kotlinx.coroutines.flow.first

/**
 * Downgrades a wallet to read-only by irreversibly removing the master key.
 *
 * This is a **self-healing verifier**: it first invokes the reconciler to
 * backfill any missing addresses (closing the insert-then-update crash window
 * from [CreateAccountUseCase]), then verifies that all native accounts have
 * a persisted address (and BTC accounts have an xpub) BEFORE clearing the
 * key. If any are still missing after reconciliation, it refuses to proceed.
 */
class RemoveMasterKeyUseCase(
    private val walletRepository: WalletRepository,
    private val accountRepository: AccountRepository,
    private val reconcileAddresses: ReconcileAddressesUseCase,
) {
    suspend operator fun invoke(walletId: Long) {
        val wallet = walletRepository.getWallet(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")
        require(!wallet.isReadOnly) { "Wallet is already read-only" }

        // Self-heal: backfill any addresses missing due to a crash between
        // insert and update in CreateAccountUseCase.
        reconcileAddresses(walletId)

        val accounts = accountRepository.getAccountsByWalletFlow(walletId).first()
            .filter { it.type.isNative }

        // Verify all native accounts have a persisted address.
        val missingAddress = accounts.filter { it.address.isNullOrBlank() }
        require(missingAddress.isEmpty()) {
            "Cannot remove master key: ${missingAddress.size} account(s) have no persisted address. " +
                "Sync the wallet first or restore from mnemonic."
        }

        // Verify all BTC accounts have a persisted xpub.
        val missingXpub = accounts
            .filter { it.type is AccountType.Btc }
            .filter { accountRepository.getXpub(it.id) == null }
        require(missingXpub.isEmpty()) {
            "Cannot remove master key: ${missingXpub.size} BTC account(s) have no persisted xpub."
        }

        walletRepository.clearMasterKey(walletId)
    }
}

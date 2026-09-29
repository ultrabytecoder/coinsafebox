package com.ultrabytecoder.coinsafebox.data

import com.ultrabytecoder.coinsafebox.domain.exception.ReadOnlyWalletException
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import com.ultrabytecoder.coinsafebox.security.SecureMnemonicCode
import com.ultrabytecoder.coinsafebox.security.gcHint
import com.ultrabytecoder.coinsafebox.security.wipe

/**
 * Loan pattern for the master seed (H-3: Memory Wiping): the seed is read from the
 * database (hardware-key-wrapped at rest on top of SQLCipher, see
 * [com.ultrabytecoder.coinsafebox.security.SecretCipher]), handed to the signing
 * block, and zeroed immediately after — it lives in memory only for the
 * milliseconds a transaction is signed.
 */
class KeyProviderImpl(
    private val walletRepository: WalletRepository
) : KeyProvider {
    override suspend fun <T> withMasterSeed(walletId: Long, block: suspend (ByteArray) -> T): T {
        val wallet = walletRepository.getWallet(walletId)
            ?: throw IllegalArgumentException("Wallet not found: $walletId")
        if (wallet.isReadOnly) throw ReadOnlyWalletException()

        val seed = walletRepository.getMasterSeed(walletId)
            ?: throw IllegalStateException("Wallet $walletId is not read-only but has no master seed")

        return try {
            block(seed)
        } finally {
            seed.wipe()
        }
    }

    override suspend fun <T> withTransientSeed(mnemonic: CharArray, passphrase: CharArray, block: suspend (ByteArray) -> T): T {
        var seed: ByteArray? = null
        try {
            SecureMnemonicCode.validate(mnemonic)
            val s = SecureMnemonicCode.toSeed(mnemonic, passphrase)
            seed = s
            return block(s)
        } finally {
            seed?.wipe()
            mnemonic.wipe()
            passphrase.wipe()
            gcHint()
        }
    }
}

package com.ultrabytecoder.coinsafebox.domain.service

interface KeyProvider {
    /**
     * Loans the wallet's master seed to [block]. The seed is read from the
     * database and wiped immediately after [block] returns. The seed lives in
     * memory only for the milliseconds a transaction is signed.
     *
     * @throws com.ultrabytecoder.coinsafebox.domain.exception.ReadOnlyWalletException
     * when the wallet is read-only (no master seed is stored on disk).
     */
    suspend fun <T> withMasterSeed(walletId: Long, block: suspend (ByteArray) -> T): T

    /** Derives a TRANSIENT seed from [mnemonic], loans it to [block], and wipes the seed + mnemonic + passphrase in a finally block. The seed never touches disk. */
    suspend fun <T> withTransientSeed(mnemonic: CharArray, passphrase: CharArray, block: suspend (ByteArray) -> T): T
}

package com.ultrabytecoder.coinsafebox.domain.repository

import com.ultrabytecoder.coinsafebox.domain.model.WalletInfo
import kotlinx.coroutines.flow.Flow

interface WalletRepository {
    fun getWalletsFlow(): Flow<List<WalletInfo>>
    suspend fun getWallet(id: Long): WalletInfo?
    suspend fun getMasterSeed(id: Long): ByteArray?
    suspend fun insertWallet(name: String, masterSeed: ByteArray, mnemonic: ByteArray?, hasPassphrase: Boolean): Long
    suspend fun deleteWallet(id: Long)

    /** Returns the wallet's stored mnemonic (plaintext), or null when absent. */
    suspend fun getStoredMnemonic(id: Long): ByteArray?
    suspend fun renameWallet(id: Long, name: String)

    /** Irreversible: NULLs both master_seed and mnemonic, making the wallet read-only. */
    suspend fun clearMasterKey(id: Long)
    suspend fun restoreMasterKey(id: Long, masterSeed: ByteArray, mnemonic: ByteArray?)
}
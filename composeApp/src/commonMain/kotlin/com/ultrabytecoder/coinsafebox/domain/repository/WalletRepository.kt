package com.ultrabytecoder.coinsafebox.domain.repository

import com.ultrabytecoder.coinsafebox.domain.model.WalletInfo
import kotlinx.coroutines.flow.Flow

interface WalletRepository {
    fun getWalletsFlow(): Flow<List<WalletInfo>>
    suspend fun getWallet(id: Long): WalletInfo?
    suspend fun getMasterSeed(id: Long): ByteArray?
    suspend fun insertWallet(name: String, masterSeed: ByteArray, mnemonic: ByteArray?): Long
    suspend fun deleteWallet(id: Long)

    /** Returns the wallet's stored mnemonic (plaintext), or null when absent. */
    suspend fun getStoredMnemonic(id: Long): ByteArray?
    suspend fun renameWallet(id: Long, name: String)
}
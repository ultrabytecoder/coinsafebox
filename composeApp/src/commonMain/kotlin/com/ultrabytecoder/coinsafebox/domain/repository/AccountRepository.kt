package com.ultrabytecoder.coinsafebox.domain.repository

import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun getAccountsByWalletFlow(walletId: Long): Flow<List<AccountInfo>>
    fun getNativeAccountsByWalletFlow(walletId: Long): Flow<List<AccountInfo>>
    fun getTokensByParentFlow(parentId: String): Flow<List<AccountInfo>>
    suspend fun getAccount(id: String): AccountInfo?
    suspend fun getMaxAccountIndexByWalletAndAccountType(walletId: Long, type: String): Long?
    suspend fun existsByDerivationPath(walletId: Long, derivationPath: String): Boolean
    suspend fun existsTokenForParent(parentId: String, tokenAddress: String): Boolean

    /** Returns the number of child token accounts linked to the given parent. */
    suspend fun countTokensByParent(parentId: String): Int
    suspend fun insertAccount(account: AccountInfo)
    suspend fun updateAmount(accountId: String, amount: String)
    suspend fun updateParams(accountId: String, params: String)
    suspend fun deleteAccount(id: String)
    suspend fun deleteAccountsByWallet(walletId: Long)

    /**
     * Returns native accounts of the given [type] (e.g. "ETH", "TRX")
     * for the given [walletId], as a one-shot snapshot sorted by accountIndex.
     */
    suspend fun getNativeAccountsByWalletAndType(
        walletId: Long,
        type: String
    ): List<AccountInfo>
}

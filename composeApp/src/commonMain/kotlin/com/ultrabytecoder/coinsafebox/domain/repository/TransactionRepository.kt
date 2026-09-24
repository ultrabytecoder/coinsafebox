package com.ultrabytecoder.coinsafebox.domain.repository

import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import kotlinx.coroutines.flow.Flow

interface TransactionRepository {
    fun getTransactionsByAccountFlow(accountId: String): Flow<List<TransactionInfo>>
    suspend fun getTransactionsByAccount(accountId: String, limit: Long, offset: Long): List<TransactionInfo>
    suspend fun getTransactionById(id: String): TransactionInfo?
    suspend fun getTransactionCount(accountId: String): Long
    suspend fun upsertAll(transactions: List<TransactionInfo>)
    suspend fun deleteByAccount(accountId: String)
}

package com.ultrabytecoder.coinsafebox.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ultrabytecoder.coinsafebox.domain.model.TransactionDirection
import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import com.ultrabytecoder.coinsafebox.domain.model.TransactionStatus
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository as TransactionRepositoryInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class TransactionRepository(private val databaseProvider: DatabaseProvider) : TransactionRepositoryInterface {
    private val queries get() = databaseProvider.database().coinSafeBoxDatabaseQueries

    override fun getTransactionsByAccountFlow(accountId: String): Flow<List<TransactionInfo>> {
        return queries.selectTransactionsByAccount(accountId, Long.MAX_VALUE, 0)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list -> list.map { it.toTransactionInfo() } }
    }

    override suspend fun getTransactionsByAccount(accountId: String, limit: Long, offset: Long): List<TransactionInfo> =
        withContext(Dispatchers.IO) {
            queries.selectTransactionsByAccount(accountId, limit, offset)
                .executeAsList()
                .map { it.toTransactionInfo() }
        }

    override suspend fun getTransactionCount(accountId: String): Long = withContext(Dispatchers.IO) {
        queries.selectTransactionCountByAccount(accountId)
            .executeAsOne()
    }

    override suspend fun getTransactionById(id: String): TransactionInfo? = withContext(Dispatchers.IO) {
        queries.selectTransactionById(id)
            .executeAsOneOrNull()
            ?.toTransactionInfo()
    }

    override suspend fun upsertAll(transactions: List<TransactionInfo>) {
        withContext(Dispatchers.IO) {
            queries.transaction {
                for (tx in transactions) {
                    queries.upsertTransaction(
                        id = tx.id,
                        account_id = tx.accountId,
                        tx_hash = tx.txHash,
                        direction = tx.direction.code,
                        amount = tx.amount,
                        fee = tx.fee,
                        timestamp = tx.timestamp,
                        status = tx.status.code,
                        counterparty_address = tx.counterpartyAddress,
                        block_height = tx.blockHeight,
                        chain_data = tx.chainData
                    )
                }
            }
        }
    }

    override suspend fun deleteByAccount(accountId: String) {
        withContext(Dispatchers.IO) {
            queries.deleteTransactionsByAccountId(accountId)
        }
    }

    private fun com.ultrabytecoder.coinsafebox.db.Transactions.toTransactionInfo(): TransactionInfo = TransactionInfo(
        id = id,
        accountId = account_id,
        txHash = tx_hash,
        direction = TransactionDirection.fromCode(direction),
        amount = amount,
        fee = fee,
        timestamp = timestamp,
        status = TransactionStatus.fromCode(status),
        counterpartyAddress = counterparty_address,
        blockHeight = block_height,
        chainData = chain_data
    )
}

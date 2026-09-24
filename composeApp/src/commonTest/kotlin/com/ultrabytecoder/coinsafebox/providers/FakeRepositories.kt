package com.ultrabytecoder.coinsafebox.providers

import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import com.ultrabytecoder.coinsafebox.domain.model.UtxoInfo
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeUtxoRepository(
    private val utxos: List<UtxoInfo> = emptyList()
) : UtxoRepository {
    override fun getUnspentByAccountFlow(accountId: String): Flow<List<UtxoInfo>> = flowOf(utxos)
    override suspend fun getUtxosByAccount(accountId: String): List<UtxoInfo> = utxos
    override suspend fun insertUtxo(utxo: UtxoInfo) {}

    override suspend fun deleteUtxo(id: Long) {}
    override suspend fun deleteUtxosByAccount(accountId: String) {}
}

class FakeAccountRepository(
    private val accounts: Map<String, AccountInfo> = emptyMap()
) : AccountRepository {
    override fun getAccountsByWalletFlow(walletId: Long): Flow<List<AccountInfo>> = flowOf(emptyList())
    override fun getTokensByParentFlow(parentId: String): Flow<List<AccountInfo>> = flowOf(emptyList())
    override fun getNativeAccountsByWalletFlow(walletId: Long): Flow<List<AccountInfo>> = flowOf(emptyList())
    override suspend fun getAccount(id: String): AccountInfo? = accounts[id]
    override suspend fun getMaxAccountIndexByWalletAndAccountType(walletId: Long, type: String): Long? = null
    override suspend fun existsByDerivationPath(walletId: Long, derivationPath: String): Boolean = false
    override suspend fun existsTokenForParent(parentId: String, tokenAddress: String): Boolean = false
    override suspend fun countTokensByParent(parentId: String): Int = 0
    override suspend fun getNativeAccountsByWalletAndType(walletId: Long, type: String): List<AccountInfo> = emptyList()
    override suspend fun insertAccount(account: AccountInfo) {}
    override suspend fun updateAmount(accountId: String, amount: String) {}
    override suspend fun updateParams(accountId: String, params: String) {}
    override suspend fun deleteAccount(id: String) {}
    override suspend fun deleteAccountsByWallet(walletId: Long) {}
}

class FakeTransactionRepository(
    private val transactions: MutableList<TransactionInfo> = mutableListOf()
) : TransactionRepository {
    override fun getTransactionsByAccountFlow(accountId: String): Flow<List<TransactionInfo>> =
        flowOf(transactions.filter { it.accountId == accountId })

    override suspend fun getTransactionsByAccount(accountId: String, limit: Long, offset: Long): List<TransactionInfo> =
        transactions.filter { it.accountId == accountId }

    override suspend fun getTransactionCount(accountId: String): Long =
        transactions.count { it.accountId == accountId }.toLong()

    override suspend fun getTransactionById(id: String): TransactionInfo? =
        transactions.find { it.id == id }

    override suspend fun upsertAll(newTransactions: List<TransactionInfo>) {
        for (tx in newTransactions) {
            val existingIndex = transactions.indexOfFirst { it.txHash == tx.txHash && it.accountId == tx.accountId }
            if (existingIndex >= 0) {
                transactions[existingIndex] = tx
            } else {
                transactions.add(tx)
            }
        }
    }

    override suspend fun deleteByAccount(accountId: String) {
        transactions.removeAll { it.accountId == accountId }
    }
}

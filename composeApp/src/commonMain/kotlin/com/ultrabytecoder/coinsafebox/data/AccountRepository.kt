package com.ultrabytecoder.coinsafebox.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository as AccountRepositoryInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AccountRepository(private val databaseProvider: DatabaseProvider) : AccountRepositoryInterface {
    private val queries get() = databaseProvider.database().coinSafeBoxDatabaseQueries

    override fun getAccountsByWalletFlow(walletId: Long): Flow<List<AccountInfo>> = flow {
        // Wrapped in flow{} so the (unlocked-DB) query is resolved only when the
        // flow is collected, not when the Flow instance is constructed.
        emitAll(
            queries.selectByWalletId(walletId)
                .asFlow()
                .mapToList(Dispatchers.Default)
                .map { list -> list.map { it.toAccountInfo() } }
        )
    }

    override fun getNativeAccountsByWalletFlow(walletId: Long): Flow<List<AccountInfo>> = flow {
        emitAll(
            queries.selectNativeAccountsByWalletId(walletId)
                .asFlow()
                .mapToList(Dispatchers.Default)
                .map { list -> list.map { it.toAccountInfo() } }
        )
    }

    override fun getTokensByParentFlow(parentId: String): Flow<List<AccountInfo>> = flow {
        emitAll(
            queries.selectTokensByParentId(parentId)
                .asFlow()
                .mapToList(Dispatchers.Default)
                .map { list -> list.map { it.toAccountInfo() } }
        )
    }

    override suspend fun getAccount(id: String): AccountInfo? = withContext(Dispatchers.IO) {
        queries.selectById(id).executeAsOneOrNull()?.toAccountInfo()
    }

    override suspend fun insertAccount(account: AccountInfo) {
        withContext(Dispatchers.IO) {
            val tokenAddress = account.type.tokenContractAddress
            queries.insert(
                id = account.id,
                wallet_id = account.walletId,
                name = account.name,
                amount = account.amount,
                type = account.type.toDbCode(),
                address = account.address,
                account_index = account.accountIndex,
                derivation_path = account.derivationPath,
                params = account.params ?: account.type.toParamsJson(),
                symbol = account.symbol,
                parent_account_id = account.parentAccountId,
                token_address = tokenAddress
            )
        }
    }

    override suspend fun getMaxAccountIndexByWalletAndAccountType(walletId: Long, type: String): Long? = withContext(Dispatchers.IO) {
        queries.maxDerivationIndexByWalletAndAccountType(walletId, type).executeAsOne().max_index
    }

    override suspend fun existsByDerivationPath(walletId: Long, derivationPath: String): Boolean = withContext(Dispatchers.IO) {
        queries.existsByDerivationPath(walletId, derivationPath).executeAsOne()
    }

    override suspend fun existsTokenForParent(parentId: String, tokenAddress: String): Boolean = withContext(Dispatchers.IO) {
        queries.existsTokenForParent(parentId, tokenAddress).executeAsOne()
    }

    override suspend fun countTokensByParent(parentId: String): Int = withContext(Dispatchers.IO) {
        queries.countTokensByParent(parentId).executeAsOne().toInt()
    }

    override suspend fun updateAmount(accountId: String, amount: String) {
        withContext(Dispatchers.IO) {
            queries.updateAmount(amount, accountId)
        }
    }

    override suspend fun updateParams(accountId: String, params: String) {
        withContext(Dispatchers.IO) {
            queries.updateParams(params, accountId)
        }
    }

    override suspend fun deleteAccount(id: String) {
        withContext(Dispatchers.IO) {
            queries.deleteTokensByParentId(id)
            queries.deleteById(id)
        }
    }

    override suspend fun deleteAccountsByWallet(walletId: Long) {
        withContext(Dispatchers.IO) {
            queries.deleteAccountsByWalletId(walletId)
        }
    }

    override suspend fun getNativeAccountsByWalletAndType(walletId: Long, type: String): List<AccountInfo> =
        withContext(Dispatchers.IO) {
            queries.selectNativeAccountsByWalletAndType(walletId, type)
                .executeAsList()
                .map { it.toAccountInfo() }
        }

    private fun com.ultrabytecoder.coinsafebox.db.Accounts.toAccountInfo(): AccountInfo = AccountInfo(
        id = id,
        walletId = wallet_id,
        name = name,
        amount = amount,
        type = AccountType.fromCode(type, params),
        symbol = symbol,
        address = address,
        accountIndex = account_index,
        derivationPath = derivation_path,
        params = params,
        parentAccountId = parent_account_id
    )
}

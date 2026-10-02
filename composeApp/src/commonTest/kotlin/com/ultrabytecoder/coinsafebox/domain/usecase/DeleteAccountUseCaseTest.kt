package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import com.ultrabytecoder.coinsafebox.domain.model.UtxoInfo
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [DeleteAccountUseCase]: the per-account-group cascade. It must
 * wipe utxos + transactions for the parent AND every child token, then remove
 * the parent row (whose repository impl cascades to the token rows) — and it
 * must only ever call `deleteAccount` on the parent, never on the tokens.
 */
class DeleteAccountUseCaseTest {

    private class RecordingAccountRepository(
        private val tokensByParent: Map<String, List<AccountInfo>>,
        val log: MutableList<String>
    ) : AccountRepository {
        val deletedAccounts = mutableListOf<String>()

        override fun getAccountsByWalletFlow(walletId: Long): Flow<List<AccountInfo>> = flowOf(emptyList())
        override fun getTokensByParentFlow(parentId: String): Flow<List<AccountInfo>> =
            flowOf(tokensByParent[parentId].orEmpty())
        override fun getNativeAccountsByWalletFlow(walletId: Long): Flow<List<AccountInfo>> = flowOf(emptyList())
        override suspend fun getAccount(id: String): AccountInfo? = null
        override suspend fun getMaxAccountIndexByWalletAndAccountType(walletId: Long, type: String): Long? = null
        override suspend fun existsByDerivationPath(walletId: Long, derivationPath: String): Boolean = false
        override suspend fun existsTokenForParent(parentId: String, tokenAddress: String): Boolean = false
        override suspend fun countTokensByParent(parentId: String): Int = 0
        override suspend fun getNativeAccountsByWalletAndType(walletId: Long, type: String): List<AccountInfo> = emptyList()
        override suspend fun insertAccount(account: AccountInfo) {}
        override suspend fun updateAmount(accountId: String, amount: String) {}
        override suspend fun updateParams(accountId: String, params: String) {}
        override suspend fun deleteAccount(id: String) {
            deletedAccounts += id
            log += "deleteAccount:$id"
        }

        override suspend fun deleteAccountsByWallet(walletId: Long) {}
        override suspend fun getXpub(id: String): String? = null
        override suspend fun updateXpub(id: String, xpub: String) {}
        override suspend fun updateAddress(id: String, address: String) {}
    }

    private class RecordingUtxoRepository(val log: MutableList<String>) : UtxoRepository {
        val deleted = mutableListOf<String>()
        override fun getUnspentByAccountFlow(accountId: String): Flow<List<UtxoInfo>> = flowOf(emptyList())
        override suspend fun getUtxosByAccount(accountId: String): List<UtxoInfo> = emptyList()
        override suspend fun insertUtxo(utxo: UtxoInfo) {}
        override suspend fun deleteUtxo(id: Long) {}
        override suspend fun deleteUtxosByAccount(accountId: String) {
            deleted += accountId
            log += "deleteUtxos:$accountId"
        }
    }

    private class RecordingTransactionRepository(val log: MutableList<String>) : TransactionRepository {
        val deleted = mutableListOf<String>()
        override fun getTransactionsByAccountFlow(accountId: String): Flow<List<TransactionInfo>> = flowOf(emptyList())
        override suspend fun getTransactionsByAccount(accountId: String, limit: Long, offset: Long): List<TransactionInfo> = emptyList()
        override suspend fun getTransactionCount(accountId: String): Long = 0
        override suspend fun getTransactionById(id: String): TransactionInfo? = null
        override suspend fun upsertAll(transactions: List<TransactionInfo>) {}
        override suspend fun deleteByAccount(accountId: String) {
            deleted += accountId
            log += "deleteTxs:$accountId"
        }
    }

    private fun parent() = AccountInfo(
        id = "parent", walletId = 1, name = "BTC #0", amount = "0",
        type = AccountType.Btc, symbol = "BTC", address = "tb1qparent",
        accountIndex = 0, derivationPath = "m/84'/0'/0'/0"
    )

    private fun token(id: String) = AccountInfo(
        id = id, walletId = 1, name = "USDC", amount = "0",
        type = AccountType.Erc20("0xtoken"), symbol = "USDC", address = null,
        accountIndex = 0, derivationPath = "m/44'/60'/0'/0/0",
        parentAccountId = "parent"
    )

    @Test
    fun wipesUtxosAndTxsForParentAndEveryToken_thenDeletesParentRow() = runTest {
        val log = mutableListOf<String>()
        val tokens = listOf(token("t1"), token("t2"))
        val accounts = RecordingAccountRepository(mapOf("parent" to tokens), log)
        val utxo = RecordingUtxoRepository(log)
        val tx = RecordingTransactionRepository(log)

        DeleteAccountUseCase(accounts, utxo, tx)("parent")

        assertEquals(setOf("t1", "t2", "parent"), utxo.deleted.toSet())
        assertEquals(setOf("t1", "t2", "parent"), tx.deleted.toSet())
        // single deleteAccount on the PARENT; token rows cascade inside the repo impl.
        assertEquals(listOf("parent"), accounts.deletedAccounts)
    }

    @Test
    fun parentOnly_whenNoTokens() = runTest {
        val log = mutableListOf<String>()
        val accounts = RecordingAccountRepository(emptyMap(), log)
        val utxo = RecordingUtxoRepository(log)
        val tx = RecordingTransactionRepository(log)

        DeleteAccountUseCase(accounts, utxo, tx)("parent")

        assertEquals(setOf("parent"), utxo.deleted.toSet())
        assertEquals(setOf("parent"), tx.deleted.toSet())
        assertEquals(listOf("parent"), accounts.deletedAccounts)
    }

    @Test
    fun deletesChildRowsBeforeTheAccountRow() = runTest {
        val log = mutableListOf<String>()
        val tokens = listOf(token("t1"), token("t2"))
        val accounts = RecordingAccountRepository(mapOf("parent" to tokens), log)
        val utxo = RecordingUtxoRepository(log)
        val tx = RecordingTransactionRepository(log)

        DeleteAccountUseCase(accounts, utxo, tx)("parent")

        // FK safety: the account-row delete runs last, after all child-row deletes.
        assertEquals("deleteAccount:parent", log.last())
        log.dropLast(1).forEach { op ->
            assertTrue(op.startsWith("deleteUtxos:") || op.startsWith("deleteTxs:"), "unexpected $op before deleteAccount")
        }
        // both tokens and the parent had their child rows wiped
        assertEquals(2, tokens.count { "deleteUtxos:${it.id}" in log && "deleteTxs:${it.id}" in log })
    }

    @Test
    fun neverDeletesTokenRowsDirectly() = runTest {
        val log = mutableListOf<String>()
        val tokens = listOf(token("t1"), token("t2"))
        val accounts = RecordingAccountRepository(mapOf("parent" to tokens), log)

        DeleteAccountUseCase(accounts, RecordingUtxoRepository(log), RecordingTransactionRepository(log))("parent")

        assertTrue(accounts.deletedAccounts.none { it in tokens.map { t -> t.id } })
    }
}

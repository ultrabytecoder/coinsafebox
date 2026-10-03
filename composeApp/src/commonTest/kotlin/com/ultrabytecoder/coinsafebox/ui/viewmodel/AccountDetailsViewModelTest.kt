package com.ultrabytecoder.coinsafebox.ui.viewmodel

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.data.SettingsStore
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.FiatCurrency
import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import com.ultrabytecoder.coinsafebox.domain.model.UtxoInfo
import com.ultrabytecoder.coinsafebox.domain.model.WalletInfo
import com.ultrabytecoder.coinsafebox.domain.provider.FiatQuoteProvider
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import com.ultrabytecoder.coinsafebox.domain.usecase.DeleteAccountUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountAddressUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountsUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SyncManager
import com.ultrabytecoder.coinsafebox.domain.usecase.SyncTransactionsUseCase
import com.ultrabytecoder.coinsafebox.providers.SyncMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [AccountDetailsViewModel.removeAccount]: on success it must emit a
 * `NavigateToAccountsList(walletId)` event (the screen navigates to the list);
 * on failure it must surface the error via the existing `error` StateFlow and
 * emit NO navigation event.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountDetailsViewModelTest {

    private val parent = AccountInfo(
        id = "parent", walletId = 1, name = "BTC #0", amount = "0",
        type = AccountType.Btc, symbol = "BTC", address = "tb1qparent",
        accountIndex = 0, derivationPath = "m/84'/0'/0'/0"
    )

    private class RecordingAccountRepository(
        private val accounts: Map<String, AccountInfo>,
        private val tokensByParent: Map<String, List<AccountInfo>> = emptyMap(),
        private val deleteThrows: Boolean = false
    ) : AccountRepository {
        val deletedAccounts = mutableListOf<String>()
        override fun getAccountsByWalletFlow(walletId: Long): Flow<List<AccountInfo>> = flowOf(emptyList())
        override fun getTokensByParentFlow(parentId: String): Flow<List<AccountInfo>> = flowOf(tokensByParent[parentId].orEmpty())
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
        override suspend fun deleteAccount(id: String) {
            if (deleteThrows) throw RuntimeException("db locked")
            deletedAccounts += id
        }

        override suspend fun deleteAccountsByWallet(walletId: Long) {}
        override suspend fun getXpub(id: String): String? = null
        override suspend fun updateXpub(id: String, xpub: String) {}
        override suspend fun updateAddress(id: String, address: String) {}
    }

    private class NoopUtxoRepository : UtxoRepository {
        override fun getUnspentByAccountFlow(accountId: String): Flow<List<UtxoInfo>> = flowOf(emptyList())
        override suspend fun getUtxosByAccount(accountId: String): List<UtxoInfo> = emptyList()
        override suspend fun insertUtxo(utxo: UtxoInfo) {}
        override suspend fun deleteUtxo(id: Long) {}
        override suspend fun deleteUtxosByAccount(accountId: String) {}
    }

    private class NoopTransactionRepository : TransactionRepository {
        override fun getTransactionsByAccountFlow(accountId: String): Flow<List<TransactionInfo>> = flowOf(emptyList())
        override suspend fun getTransactionsByAccount(accountId: String, limit: Long, offset: Long): List<TransactionInfo> = emptyList()
        override suspend fun getTransactionCount(accountId: String): Long = 0
        override suspend fun getTransactionById(id: String): TransactionInfo? = null
        override suspend fun upsertAll(transactions: List<TransactionInfo>) {}
        override suspend fun deleteByAccount(accountId: String) {}
    }

    private class FakeWalletRepository(private val wallet: WalletInfo?) : WalletRepository {
        override fun getWalletsFlow(): Flow<List<WalletInfo>> = flowOf(wallet?.let { listOf(it) } ?: emptyList())
        override suspend fun getWallet(id: Long): WalletInfo? = wallet
        override suspend fun getMasterSeed(id: Long): ByteArray? = null
        override suspend fun insertWallet(name: String, masterSeed: ByteArray, mnemonic: ByteArray?, hasPassphrase: Boolean): Long = 1
        override suspend fun deleteWallet(id: Long) {}
        override suspend fun getStoredMnemonic(id: Long): ByteArray? = null
        override suspend fun renameWallet(id: Long, name: String) {}
        override suspend fun clearMasterKey(id: Long) {}
        override suspend fun restoreMasterKey(id: Long, masterSeed: ByteArray, mnemonic: ByteArray?) {}
    }

    private class InMemorySettingsStore : SettingsStore {
        private val map = mutableMapOf<String, String>()
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getString(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
    }

    private class StubKeyProvider : KeyProvider {
        override suspend fun <T> withMasterSeed(walletId: Long, block: suspend (ByteArray) -> T): T = block(ByteArray(0))
        override suspend fun <T> withTransientSeed(mnemonic: CharArray, passphrase: CharArray, block: suspend (ByteArray) -> T): T = block(ByteArray(0))
    }

    private class StubQuoteProvider : FiatQuoteProvider {
        override suspend fun getPrice(cryptoSymbol: String, fiat: FiatCurrency): Double = 0.0
    }

    private class NoopSyncTransactionsUseCase(
        accountRepository: AccountRepository,
        utxoRepository: UtxoRepository,
        transactionRepository: TransactionRepository,
        keyProvider: KeyProvider,
        networkConfig: NetworkConfig,
        syncManager: SyncManager,
        walletRepository: WalletRepository
    ) : SyncTransactionsUseCase(
        accountRepository, utxoRepository, transactionRepository, keyProvider, networkConfig, syncManager, walletRepository
    ) {
        override suspend operator fun invoke(accountId: String, syncMode: SyncMode) {}
    }

    private fun buildViewModel(repo: RecordingAccountRepository): AccountDetailsViewModel {
        val walletRepo = FakeWalletRepository(WalletInfo(1L, "My wallet", isReadOnly = false, hasPassphrase = false))
        val utxo = NoopUtxoRepository()
        val tx = NoopTransactionRepository()
        val syncManager = SyncManager()
        val syncTransactionsUseCase = NoopSyncTransactionsUseCase(
            repo, utxo, tx, StubKeyProvider(), NetworkConfig.testnet("test-key"), syncManager, walletRepo
        )
        return AccountDetailsViewModel(
            accountId = "parent",
            preselectedTokenId = null,
            getAccounts = GetAccountsUseCase(repo),
            getAccountAddress = GetAccountAddressUseCase(
                repo, utxo, tx, StubKeyProvider(), NetworkConfig.testnet("test-key"), walletRepo
            ),
            accountRepository = repo,
            transactionRepository = tx,
            walletRepository = walletRepo,
            deleteAccountUseCase = DeleteAccountUseCase(repo, utxo, tx),
            syncTransactionsUseCase = syncTransactionsUseCase,
            syncManager = syncManager,
            settingsStorage = InMemorySettingsStore(),
            quoteProvider = StubQuoteProvider()
        )
    }

    @Test
    fun removeAccount_success_emitsNavigateToAccountsListWithWalletId() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val repo = RecordingAccountRepository(mapOf(parent.id to parent))
            val vm = buildViewModel(repo)
            testScheduler.advanceUntilIdle()
            // init must have loaded the parent before we can remove it
            assertEquals(parent.id, vm.uiState.value.parent?.id)

            val events = mutableListOf<AccountDetailsEvent>()
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.events.collect { events += it }
            }

            vm.removeAccount()
            testScheduler.advanceUntilIdle()
            collector.cancel()

            assertEquals(1, events.size, "expected exactly one navigation event, got $events")
            assertEquals(AccountDetailsEvent.NavigateToAccountsList(1L), events.single())
            assertTrue(repo.deletedAccounts.contains("parent"), "account row should be deleted")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun removeAccount_failure_setsErrorAndEmitsNoEvent() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val repo = RecordingAccountRepository(
                mapOf(parent.id to parent), deleteThrows = true
            )
            val vm = buildViewModel(repo)
            testScheduler.advanceUntilIdle()
            assertEquals(parent.id, vm.uiState.value.parent?.id, "parent should be loaded before removal")

            val events = mutableListOf<AccountDetailsEvent>()
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.events.collect { events += it }
            }

            vm.removeAccount()
            testScheduler.advanceUntilIdle()
            collector.cancel()

            assertTrue(events.isEmpty(), "no navigation event on failure, got $events")
            // `vm.error` is a Lazily-started derived flow (no subscriber -> seed null);
            // the authoritative value lives on uiState.
            assertEquals("db locked", vm.uiState.value.error)
            assertTrue(repo.deletedAccounts.isEmpty(), "no delete should have been recorded")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun removeAccount_whenParentNotLoaded_isNoOp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            // Repo has no account -> init fails -> parent stays null.
            val repo = RecordingAccountRepository(emptyMap())
            val vm = buildViewModel(repo)
            testScheduler.advanceUntilIdle()
            assertEquals(null, vm.uiState.value.parent)

            val events = mutableListOf<AccountDetailsEvent>()
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.events.collect { events += it }
            }
            vm.removeAccount()
            testScheduler.advanceUntilIdle()
            collector.cancel()

            assertTrue(events.isEmpty(), "no event when there is no account to remove")
        } finally {
            Dispatchers.resetMain()
        }
    }
}

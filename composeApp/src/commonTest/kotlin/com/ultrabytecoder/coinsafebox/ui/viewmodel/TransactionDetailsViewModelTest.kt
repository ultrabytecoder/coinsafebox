package com.ultrabytecoder.coinsafebox.ui.viewmodel

import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.TransactionDirection
import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import com.ultrabytecoder.coinsafebox.domain.model.TransactionStatus
import com.ultrabytecoder.coinsafebox.providers.FakeAccountRepository
import com.ultrabytecoder.coinsafebox.providers.FakeTransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionDetailsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val account = AccountInfo(
        id = "acc-1",
        walletId = 1,
        name = "Test",
        amount = "0",
        type = AccountType.Trx,
        symbol = "TRX",
        address = null,
        accountIndex = 0,
        derivationPath = "m/44'/195'/0'/0/0"
    )

    private val tx = TransactionInfo(
        id = "tx-1",
        accountId = "acc-1",
        txHash = "abc123",
        direction = TransactionDirection.INCOMING,
        amount = "1000000",
        fee = "100",
        timestamp = 1700000000000,
        status = TransactionStatus.CONFIRMED,
        counterpartyAddress = "TRON_ADDR",
        blockHeight = 12345
    )

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadTransaction_found_emitsLoadedWithAccount() = runTest(dispatcher) {
        val txRepo = FakeTransactionRepository(mutableListOf(tx))
        val accountsRepo = FakeAccountRepository(mapOf(account.id to account))
        val viewModel = TransactionDetailsViewModel(
            txId = tx.id,
            transactionRepository = txRepo,
            getAccounts = com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountsUseCase(accountsRepo)
        )

        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<TransactionDetailsUiState.Loaded>(state)
        assertEquals(tx, state.transaction)
        assertEquals(account, state.account)
    }

    @Test
    fun loadTransaction_notFound_emitsError() = runTest(dispatcher) {
        val txRepo = FakeTransactionRepository(mutableListOf())
        val viewModel = TransactionDetailsViewModel(
            txId = "missing",
            transactionRepository = txRepo,
            getAccounts = com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountsUseCase(FakeAccountRepository())
        )

        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<TransactionDetailsUiState.Error>(state)
        assertEquals("Transaction not found", state.message)
    }

    @Test
    fun loadTransaction_accountMissing_doesNotCrashAndKeepsNullAccount() = runTest(dispatcher) {
        val txRepo = FakeTransactionRepository(mutableListOf(tx))
        val accountsRepo = FakeAccountRepository(emptyMap())
        val viewModel = TransactionDetailsViewModel(
            txId = tx.id,
            transactionRepository = txRepo,
            getAccounts = com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountsUseCase(accountsRepo)
        )

        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<TransactionDetailsUiState.Loaded>(state)
        assertEquals(tx, state.transaction)
        assertEquals(null, state.account)
    }

    @Test
    fun loadTransaction_repositoryThrows_emitsError() = runTest(dispatcher) {
        val failingRepo = object : com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository {
            override fun getTransactionsByAccountFlow(accountId: String) = kotlinx.coroutines.flow.flowOf<List<TransactionInfo>>(emptyList())
            override suspend fun getTransactionsByAccount(accountId: String, limit: Long, offset: Long) = emptyList<TransactionInfo>()
            override suspend fun getTransactionById(id: String): TransactionInfo? =
                throw RuntimeException("db exploded")
            override suspend fun getTransactionCount(accountId: String): Long = 0
            override suspend fun upsertAll(transactions: List<TransactionInfo>) {}
            override suspend fun deleteByAccount(accountId: String) {}
        }
        val viewModel = TransactionDetailsViewModel(
            txId = "tx-1",
            transactionRepository = failingRepo,
            getAccounts = com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountsUseCase(FakeAccountRepository())
        )

        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<TransactionDetailsUiState.Error>(state)
        assertTrue(state.message.contains("db exploded"))
    }
}
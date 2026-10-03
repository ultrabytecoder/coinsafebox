package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.data.SettingsKeys
import com.ultrabytecoder.coinsafebox.data.SettingsStore
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.FiatCurrency
import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import com.ultrabytecoder.coinsafebox.domain.provider.FiatQuoteProvider
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import com.ultrabytecoder.coinsafebox.domain.usecase.DeleteAccountUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountAddressUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountsUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SyncManager
import com.ultrabytecoder.coinsafebox.domain.usecase.SyncTransactionsUseCase
import com.ultrabytecoder.coinsafebox.providers.SyncMode
import com.ultrabytecoder.coinsafebox.ui.util.formatFiat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountDetailUiState(
    val parent: AccountInfo? = null,
    val tokens: List<AccountInfo> = emptyList(),
    val selectedAccount: AccountInfo? = null,
    val address: String? = null,
    val transactions: List<TransactionInfo> = emptyList(),
    val isLoadingMore: Boolean = false,
    val isSyncingTransactions: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null
)

sealed class AccountDetailsEvent {
    data class NavigateToAccountsList(val walletId: Long) : AccountDetailsEvent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDetailsViewModel(
    val accountId: String,
    val preselectedTokenId: String?,
    private val getAccounts: GetAccountsUseCase,
    private val getAccountAddress: GetAccountAddressUseCase,
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val walletRepository: WalletRepository,
    private val deleteAccountUseCase: DeleteAccountUseCase,
    private val syncTransactionsUseCase: SyncTransactionsUseCase,
    private val syncManager: SyncManager,
    settingsStorage: SettingsStore,
    private val quoteProvider: FiatQuoteProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountDetailUiState())
    val uiState: StateFlow<AccountDetailUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AccountDetailsEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AccountDetailsEvent> = _events.asSharedFlow()

    private val _isReadOnly = MutableStateFlow(false)
    val isReadOnly: StateFlow<Boolean> = _isReadOnly.asStateFlow()

    // Legacy flows for backward compat
    val account: StateFlow<AccountInfo?> = _uiState.map { it.parent }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val address: StateFlow<String?> = _uiState.map { it.address }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val transactions: StateFlow<List<TransactionInfo>> = _uiState.map { it.transactions }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val isLoadingMore: StateFlow<Boolean> = _uiState.map { it.isLoadingMore }.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val hasMore: StateFlow<Boolean> = _uiState.map { it.hasMore }.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val error: StateFlow<String?> = _uiState.map { it.error }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val fiatCurrency: StateFlow<FiatCurrency> = MutableStateFlow(
        FiatCurrency.fromStored(settingsStorage.getString(SettingsKeys.FIAT_CURRENCY))
    ).asStateFlow()

    private val _selectedAccountFlow = MutableStateFlow<AccountInfo?>(null)

    val selectedFiatBalance: StateFlow<String?> = combine(
        _selectedAccountFlow,
        fiatCurrency
    ) { selected, currency ->
        if (selected == null) return@combine null
        val amount = selected.amount.toDoubleOrNull() ?: 0.0
        val price = quoteProvider.getPrice(selected.symbol, currency)
        formatFiat(amount * price, currency.code)
    }
    .stateIn(viewModelScope, SharingStarted.Lazily, null)

    init {
        viewModelScope.launch {
            try {
                val parent = getAccounts.byId(accountId)
                    ?: throw IllegalStateException("Account not found: $accountId")
                val tokens = accountRepository.getTokensByParentFlow(accountId).first()
                val addr = getAccountAddress(accountId)

                // Determine which account to select (preselected token or parent)
                val selected = preselectedTokenId?.let { id ->
                    tokens.find { it.id == id } ?: parent
                } ?: parent

                _selectedAccountFlow.value = selected
                _uiState.value = AccountDetailUiState(
                    parent = parent,
                    tokens = tokens,
                    selectedAccount = selected,
                    address = addr
                )
                _isReadOnly.value = walletRepository.getWallet(parent.walletId)?.isReadOnly ?: false

                // The transactions list is synced at Account Details startup. The reactive
                // flow below re-emits when the sync's upserts commit, so no manual re-read.
                triggerTxSync(selected.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to load account details")
            }
        }
        // Follow the selected account and re-emit on every DB write (e.g. syncTransactions'
        // upsertAll) so an already-open screen picks up new transactions live.
        viewModelScope.launch {
            _selectedAccountFlow
                .map { it?.id }
                .filterNotNull()
                .flatMapLatest { id -> transactionRepository.getTransactionsByAccountFlow(id) }
                .collect { txs ->
                    _uiState.update { it.copy(transactions = txs, hasMore = false, isLoadingMore = false) }
                }
        }
        // Spinner: is the currently selected account being transaction-synced?
        viewModelScope.launch {
            _selectedAccountFlow
                .map { it?.id }
                .filterNotNull()
                .flatMapLatest { id -> syncManager.syncingTransactionsAccounts.map { id in it } }
                .collect { syncing ->
                    _uiState.update { it.copy(isSyncingTransactions = syncing) }
                }
        }
    }

    fun selectAccount(account: AccountInfo) {
        _selectedAccountFlow.value = account
        _uiState.value = _uiState.value.copy(selectedAccount = account, transactions = emptyList())
        // A chip tap is a fresh view of another account: sync its transactions too.
        viewModelScope.launch { triggerTxSync(account.id) }
    }

    private suspend fun triggerTxSync(accountId: String) {
        // FULL on a first-ever open of an account (no cached txs) to backfill history;
        // NORMAL (incremental) afterwards. The committed upserts notify the reactive
        // transactions flow (SQLDelight asFlow), which refreshes the list.
        val mode = if (transactionRepository.getTransactionCount(accountId) == 0L) SyncMode.FULL else SyncMode.NORMAL
        syncTransactionsUseCase(accountId, mode)
    }

    fun loadNextPage() {
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun removeAccount() {
        val parent = _uiState.value.parent ?: return
        viewModelScope.launch {
            try {
                deleteAccountUseCase(parent.id)
                _events.emit(AccountDetailsEvent.NavigateToAccountsList(parent.walletId))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to remove account")
            }
        }
    }
}

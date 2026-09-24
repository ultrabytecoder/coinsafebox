package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.data.SettingsKeys
import com.ultrabytecoder.coinsafebox.data.SettingsStorage
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.FiatCurrency
import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import com.ultrabytecoder.coinsafebox.domain.provider.FiatQuoteProvider
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountAddressUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountsUseCase
import com.ultrabytecoder.coinsafebox.ui.util.formatFiat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountDetailUiState(
    val parent: AccountInfo? = null,
    val tokens: List<AccountInfo> = emptyList(),
    val selectedAccount: AccountInfo? = null,
    val address: String? = null,
    val transactions: List<TransactionInfo> = emptyList(),
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null
)

class AccountDetailsViewModel(
    val accountId: String,
    val preselectedTokenId: String?,
    private val getAccounts: GetAccountsUseCase,
    private val getAccountAddress: GetAccountAddressUseCase,
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    settingsStorage: SettingsStorage,
    private val quoteProvider: FiatQuoteProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountDetailUiState())
    val uiState: StateFlow<AccountDetailUiState> = _uiState.asStateFlow()

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

    private val pageSize = 20L

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
                loadTransactions(selected.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to load account details")
            }
        }
    }

    fun selectAccount(account: AccountInfo) {
        _selectedAccountFlow.value = account
        _uiState.value = _uiState.value.copy(
            selectedAccount = account,
            transactions = emptyList(),
            hasMore = true
        )
        viewModelScope.launch { loadTransactions(account.id) }
    }

    fun loadNextPage() {
        val selectedId = _uiState.value.selectedAccount?.id ?: return
        viewModelScope.launch { loadTransactions(selectedId) }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private suspend fun loadTransactions(accountId: String) {
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMore) return
        _uiState.value = _uiState.value.copy(isLoadingMore = true)
        try {
            val currentTxs = _uiState.value.transactions
            val newItems = transactionRepository.getTransactionsByAccount(
                accountId, pageSize, currentTxs.size.toLong()
            )
            _uiState.value = _uiState.value.copy(
                hasMore = newItems.size == pageSize.toInt(),
                transactions = currentTxs + newItems
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to load transactions")
        } finally {
            _uiState.value = _uiState.value.copy(isLoadingMore = false)
        }
    }
}

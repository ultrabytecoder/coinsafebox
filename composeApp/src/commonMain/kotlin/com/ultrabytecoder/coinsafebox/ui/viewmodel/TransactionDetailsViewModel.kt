package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TransactionDetailsUiState {
    data object Loading : TransactionDetailsUiState
    data class Loaded(
        val transaction: TransactionInfo,
        val account: AccountInfo?
    ) : TransactionDetailsUiState
    data class Error(val message: String) : TransactionDetailsUiState
}

class TransactionDetailsViewModel(
    private val txId: String,
    private val transactionRepository: TransactionRepository,
    private val getAccounts: GetAccountsUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow<TransactionDetailsUiState>(TransactionDetailsUiState.Loading)
    val state: StateFlow<TransactionDetailsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = runCatching {
                val tx = transactionRepository.getTransactionById(txId)
                    ?: return@runCatching TransactionDetailsUiState.Error("Transaction not found")
                val account = getAccounts.byId(tx.accountId)
                TransactionDetailsUiState.Loaded(tx, account)
            }.getOrElse { TransactionDetailsUiState.Error(it.message ?: "Failed to load transaction") }
        }
    }
}
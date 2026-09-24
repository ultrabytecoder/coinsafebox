package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.usecase.AddTokenUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

@OptIn(ExperimentalCoroutinesApi::class)
class AddTokenViewModel(
    val walletId: Long,
    private val addTokenUseCase: AddTokenUseCase,
    private val accountRepository: AccountRepository,
    private val networkConfig: NetworkConfig
) : ViewModel() {

    private val _selectedParentId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val selectedParentId: StateFlow<String?> = _selectedParentId

    val parentAccounts: StateFlow<List<AccountInfo>> = accountRepository
        .getNativeAccountsByWalletFlow(walletId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** All accounts (including tokens) for this wallet — needed to check which parents have tokens. */
    val allAccounts: StateFlow<List<AccountInfo>> = accountRepository
        .getAccountsByWalletFlow(walletId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val alreadyAddedTokens: StateFlow<Set<String>> = _selectedParentId
        .flatMapLatest { parentId ->
            if (parentId != null) {
                accountRepository.getTokensByParentFlow(parentId)
                    .map { tokens ->
                        tokens.mapNotNull { it.type.tokenContractAddress }.toSet()
                    }
            } else {
                emptyFlow<Set<String>>()
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    fun selectParent(parentId: String?) {
        _selectedParentId.value = parentId
    }

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error

    suspend fun addToken(
        parentAccountId: String,
        type: AccountType,
        symbol: String
    ): Boolean {
        return runCatching {
            addTokenUseCase(parentAccountId, type, symbol)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            _error.emit(e.message ?: "Failed to add token")
        }.isSuccess
    }
}
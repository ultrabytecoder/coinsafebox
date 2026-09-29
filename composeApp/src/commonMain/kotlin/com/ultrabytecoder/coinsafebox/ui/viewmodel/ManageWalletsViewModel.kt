package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.domain.model.WalletInfo
import com.ultrabytecoder.coinsafebox.domain.usecase.DeleteWalletUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetWalletsUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.RenameWalletUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.RemoveMasterKeyUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ManageWalletsViewModel(
    getWallets: GetWalletsUseCase,
    private val deleteWalletUseCase: DeleteWalletUseCase,
    private val renameWalletUseCase: RenameWalletUseCase,
    private val removeMasterKeyUseCase: RemoveMasterKeyUseCase
) : ViewModel() {

    val wallets: StateFlow<List<WalletInfo>> = getWallets()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _removeMasterKeyError = MutableStateFlow<String?>(null)
    val removeMasterKeyError: StateFlow<String?> = _removeMasterKeyError.asStateFlow()

    fun deleteWallet(id: Long) {
        viewModelScope.launch {
            deleteWalletUseCase(id)
        }
    }

    fun renameWallet(id: Long, name: String) {
        viewModelScope.launch {
            renameWalletUseCase(id, name)
        }
    }

    fun removeMasterKey(id: Long) {
        _removeMasterKeyError.value = null
        viewModelScope.launch {
            try {
                removeMasterKeyUseCase(id)
            } catch (e: Exception) {
                _removeMasterKeyError.value = e.message ?: "Could not remove master key"
            }
        }
    }

    fun clearRemoveMasterKeyError() {
        _removeMasterKeyError.value = null
    }
}

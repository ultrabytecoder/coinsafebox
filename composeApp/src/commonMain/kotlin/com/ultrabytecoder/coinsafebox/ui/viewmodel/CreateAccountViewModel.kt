package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.usecase.CreateAccountUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.CreateTokenUseCase
import com.ultrabytecoder.coinsafebox.providers.DerivationPathResolver

class CreateAccountViewModel(
    val walletId: Long,
    private val createAccountUseCase: CreateAccountUseCase,
    private val createTokenUseCase: CreateTokenUseCase,
    private val accountRepository: AccountRepository,
    private val networkConfig: NetworkConfig
) : ViewModel() {

    suspend fun getDefaultDerivationPath(type: AccountType): String {
        require(type.isNative) { "Tokens do not have derivation paths" }
        val maxIndex = accountRepository.getMaxAccountIndexByWalletAndAccountType(walletId, type.toDbCode())
        val nextIndex = (maxIndex ?: -1) + 1
        return DerivationPathResolver.defaultPath(type, nextIndex, networkConfig)
    }

    suspend fun createAccount(
        displayName: String,
        type: AccountType,
        symbol: String,
        params: String? = null,
        derivationPath: String? = null
    ) = createAccountUseCase(walletId, displayName, type, symbol, params, derivationPath)

    suspend fun createToken(
        tokenType: AccountType,
        symbol: String
    ): CreateTokenUseCase.Result = createTokenUseCase(walletId, tokenType, symbol)
}

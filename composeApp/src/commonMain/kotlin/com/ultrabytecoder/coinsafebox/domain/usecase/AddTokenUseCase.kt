package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AddTokenUseCase(
    private val accountRepository: AccountRepository
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(
        parentAccountId: String,
        type: AccountType,
        symbol: String
    ) {
        require(type.isToken) { "Use CreateAccountUseCase for native chains" }

        val parent = accountRepository.getAccount(parentAccountId)
            ?: error("Parent account not found: $parentAccountId")

        val parentChain = type.parentChain()
            ?: error("Token type ${type.toDbCode()} has no parent chain")

        require(parent.type.toDbCode() == parentChain.toDbCode()) {
            "Token chain does not match parent chain"
        }

        val tokenAddress = type.tokenContractAddress
            ?: error("Token type ${type.toDbCode()} has no contract address")

        require(!accountRepository.existsTokenForParent(parentAccountId, tokenAddress)) {
            "Token already added to this account"
        }

        val token = AccountInfo(
            id = Uuid.random().toString(),
            walletId = parent.walletId,
            name = symbol,
            amount = "0",
            type = type,
            symbol = symbol,
            address = parent.address,
            accountIndex = parent.accountIndex,
            derivationPath = parent.derivationPath,
            params = type.toParamsJson(),
            parentAccountId = parentAccountId
        )
        accountRepository.insertAccount(token)
    }
}
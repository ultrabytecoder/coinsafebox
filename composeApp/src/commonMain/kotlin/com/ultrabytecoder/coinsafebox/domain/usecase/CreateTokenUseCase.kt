package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository

class CreateTokenUseCase(
    private val accountRepository: AccountRepository,
    private val createAccountUseCase: CreateAccountUseCase,
    private val addTokenUseCase: AddTokenUseCase
) {

    sealed interface Result {
        /** Token was created and linked to [parentAccountId]. */
        data class Created(val parentAccountId: String) : Result
        /**
         * Multiple parent accounts of the required chain exist.
         * UI must navigate to AddTokenScreen so the user can pick the parent.
         */
        data class NeedsParentSelection(
            val tokenTypeCode: String,
            val tokenAddress: String,
            val tokenType: AccountType,
            val symbol: String
        ) : Result
    }

    suspend operator fun invoke(
        walletId: Long,
        tokenType: AccountType,
        symbol: String
    ): Result {
        require(tokenType.isToken) { "CreateTokenUseCase only handles tokens" }
        require(walletId > 0) { "walletId must be positive" }

        val parentChain = tokenType.parentChain()
            ?: error("Token has no parent chain: $tokenType")

        val tokenAddress = tokenType.tokenContractAddress
            ?: error("Token has no contract address: $tokenType")

        val existingParents = accountRepository.getNativeAccountsByWalletAndType(
            walletId,
            parentChain.toDbCode()
        )

        val suitableParents = existingParents.filter { parent ->
            !accountRepository.existsTokenForParent(parent.id, tokenAddress)
        }

        return when {
            suitableParents.isEmpty() -> {
                // No suitable parent (either none exist or all already have this token) —
                // auto-create a new parent and link token.
                val (displayName, parentSymbol) = parentDisplayInfo(parentChain)
                val newParentId = createAccountUseCase(
                    walletId = walletId,
                    displayName = displayName,
                    type = parentChain,
                    symbol = parentSymbol
                )
                addTokenUseCase(newParentId, tokenType, symbol)
                Result.Created(newParentId)
            }

            suitableParents.size == 1 -> {
                // Single eligible parent — link token to it.
                addTokenUseCase(suitableParents.first().id, tokenType, symbol)
                Result.Created(suitableParents.first().id)
            }

            else -> {
                // 2+ suitable parents — let the user pick.
                Result.NeedsParentSelection(
                    tokenTypeCode = tokenType.toDbCode(),
                    tokenAddress = tokenAddress,
                    tokenType = tokenType,
                    symbol = symbol
                )
            }
        }
    }

    private fun parentDisplayInfo(parent: AccountType): Pair<String, String> = when (parent) {
        is AccountType.Eth -> "Ethereum (ETH)" to "ETH"
        is AccountType.Trx -> "TRON (TRX)" to "TRX"
        is AccountType.Ton -> "GRAM" to "GRAM"
        else -> error("Unsupported parent chain: $parent")
    }
}
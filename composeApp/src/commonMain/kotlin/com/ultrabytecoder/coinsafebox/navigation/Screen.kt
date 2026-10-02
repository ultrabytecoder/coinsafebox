package com.ultrabytecoder.coinsafebox.navigation

import kotlinx.serialization.Serializable

sealed interface Screen {
    @Serializable
    data object Startup : Screen

    @Serializable
    data object Welcome : Screen

    @Serializable
    data object CreateWallet : Screen

    @Serializable
    data class AccountsList(val walletId: Long) : Screen

    @Serializable
    data class AccountDetails(val accountId: String, val preselectedTokenId: String? = null) : Screen

    @Serializable
    data class Send(val accountId: String) : Screen

    @Serializable
    data class TransactionSent(val txId: String) : Screen

    @Serializable
    data class TransactionDetails(val txId: String) : Screen

    @Serializable
    data class CreateAccount(val walletId: Long) : Screen

    @Serializable
    data class AddToken(
        val walletId: Long,
        val preselectedTokenAddress: String? = null,
        val preselectedTokenType: String? = null,
        val requireManualSelection: Boolean = false
    ) : Screen

    @Serializable
    data class ExportMnemonic(val walletId: Long) : Screen

    @Serializable
    data object ManageWallets : Screen

    @Serializable
    data object ChooseSecurityMethod : Screen

    @Serializable
    data object SetupPin : Screen

    @Serializable
    data object SetupPassword : Screen

    @Serializable
    data object EnterPin : Screen

    @Serializable
    data object Settings : Screen

    @Serializable
    data object ChangePin : Screen

    @Serializable
    data object CustomNodes : Screen

    @Serializable
    data class WcPair(val walletId: Long) : Screen

    @Serializable
    data class WcSessionProposal(val proposalId: Long, val walletId: Long) : Screen

    @Serializable
    data object WcSessions : Screen
}

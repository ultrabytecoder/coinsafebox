package com.ultrabytecoder.coinsafebox.domain.model

data class AccountInfo(
    val id: String,
    val walletId: Long,
    val name: String,
    val amount: String,
    val type: AccountType,
    val symbol: String,
    val address: String?,
    val accountIndex: Long?,
    val derivationPath: String,
    val params: String? = null,
    val parentAccountId: String? = null
)

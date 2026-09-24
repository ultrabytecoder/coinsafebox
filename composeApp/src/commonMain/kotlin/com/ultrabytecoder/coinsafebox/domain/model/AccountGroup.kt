package com.ultrabytecoder.coinsafebox.domain.model

data class AccountGroup(
    val parent: AccountInfo,
    val tokens: List<AccountInfo>
)
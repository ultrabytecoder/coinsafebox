package com.ultrabytecoder.coinsafebox.domain.model

data class WalletInfo(
    val id: Long,
    val name: String,
    val isReadOnly: Boolean,
    val hasPassphrase: Boolean
)

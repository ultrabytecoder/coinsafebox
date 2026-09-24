package com.ultrabytecoder.coinsafebox.domain.model

data class TransactionInfo(
    val id: String,
    val accountId: String,
    val txHash: String,
    val direction: TransactionDirection,
    val amount: String,
    val fee: String?,
    val timestamp: Long,
    val status: TransactionStatus,
    val counterpartyAddress: String?,
    val blockHeight: Long?,
    val chainData: String? = null
)

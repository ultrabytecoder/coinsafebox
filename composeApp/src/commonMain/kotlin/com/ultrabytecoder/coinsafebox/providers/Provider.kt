package com.ultrabytecoder.coinsafebox.providers

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams
import com.ultrabytecoder.coinsafebox.domain.model.FeeEstimation
import com.ultrabytecoder.coinsafebox.domain.model.FeePresets

enum class SyncMode { FULL, NORMAL }

interface Provider {
    suspend fun getAddress(accountId: String): String
    suspend fun createTransaction(
        address: String,
        amount: BigDecimal,
        accountId: String,
        feeParams: CustomFeeParams? = null
    ): String
    suspend fun broadcast(rawTransaction: String): String
    suspend fun send(address: String, amount: BigDecimal, accountId: String): String
    suspend fun sync(accountId: String, syncMode: SyncMode = SyncMode.NORMAL) {}
    suspend fun balance(accountId: String): BigDecimal
    suspend fun estimateFee(
        accountId: String,
        amount: BigDecimal,
        recipientAddress: String? = null,
        feeParams: CustomFeeParams? = null
    ): FeeEstimation
    suspend fun feePresets(accountId: String): FeePresets?
}

package com.ultrabytecoder.coinsafebox.ui.util

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

private const val BTC_DECIMALS = 8
private const val ETH_DECIMALS = 18
private const val TON_DECIMALS = 9
private const val TRX_DECIMALS = 6

fun formatAmount(rawAmount: String, accountType: AccountType, chainData: String? = null): String {
    return when (accountType) {
        is AccountType.Btc -> formatNative(rawAmount, BTC_DECIMALS, "BTC")
        is AccountType.Eth -> formatNative(rawAmount, ETH_DECIMALS, "ETH")
        is AccountType.Ton -> formatNative(rawAmount, TON_DECIMALS, "GRAM")
        is AccountType.Trx -> formatNative(rawAmount, TRX_DECIMALS, "TRX")
        is AccountType.Erc20 -> formatToken(rawAmount, chainData)
        is AccountType.Trc20 -> formatToken(rawAmount, chainData)
        is AccountType.TonToken -> formatToken(rawAmount, chainData)
    }
}

private fun formatNative(rawAmount: String, decimals: Int, ticker: String): String {
    val raw = BigDecimal.parseString(rawAmount)
    val divisor = BigDecimal.fromLong(10).pow(decimals.toLong())
    val coinAmount = raw.divide(divisor, decimalMode = com.ionspin.kotlin.bignum.decimal.DecimalMode(decimalPrecision = decimals.toLong(), roundingMode = RoundingMode.FLOOR))
    val formatted = coinAmount.toPlainString()
    return "$formatted $ticker"
}

private fun formatToken(rawAmount: String, chainData: String?): String {
    if (chainData == null) return rawAmount
    val obj = json.parseToJsonElement(chainData).jsonObject
    val decimals = obj["tokenDecimal"]?.jsonPrimitive?.content?.toIntOrNull()
        ?: obj["decimals"]?.jsonPrimitive?.content?.toIntOrNull()
        ?: 18
    val symbol = obj["tokenSymbol"]?.jsonPrimitive?.content
        ?: obj["symbol"]?.jsonPrimitive?.content
        ?: "TOKEN"
    return formatNative(rawAmount, decimals, symbol)
}

package com.ultrabytecoder.coinsafebox.ui.util

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams

private fun formatTrxValue(sun: Long): String {
    val trxValue = BigDecimal.fromLong(sun).divide(
        BigDecimal.fromLong(1_000_000),
        decimalMode = com.ionspin.kotlin.bignum.decimal.DecimalMode(
            decimalPrecision = 6,
            roundingMode = RoundingMode.ROUND_HALF_AWAY_FROM_ZERO
        )
    )
    return trxValue.toPlainString()
}

/**
 * Format a milli-Gwei value (1 mGwei = 0.001 Gwei) as a Gwei string.
 * Examples: 25_000 -> "25", 50 -> "0.05", 1_500 -> "1.5", -500 -> "-0.5".
 */
fun formatGwei(milliGwei: Long): String {
    val sign = if (milliGwei < 0) "-" else ""
    val abs = if (milliGwei == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(milliGwei)
    val whole = abs / 1000
    val frac = (abs % 1000).toString().padStart(3, '0').trimEnd('0')
    return if (frac.isEmpty()) "$sign$whole" else "$sign$whole.$frac"
}

/**
 * Parse a user-entered Gwei string (e.g. "25" or "0.05") into milli-Gwei.
 * Returns `null` when the input is not a valid number.
 */
fun parseGweiToMilliGwei(text: String): Long? {
    val d = text.toDoubleOrNull() ?: return null
    return (d * 1000).toLong()
}

/**
 * Format [CustomFeeParams] into a short, human-readable string suitable for
 * display inside a fee-selection chip.
 *
 * Returns `null` when no params are available.
 */
fun formatFeeChipRate(params: CustomFeeParams?): String? {
    if (params == null) return null
    return when (params) {
        is CustomFeeParams.Btc -> "${params.feeRateSatVb} sat/vB"
        is CustomFeeParams.Eth -> "${formatGwei(params.maxFeePerGasMilliGwei)} Gwei"
        is CustomFeeParams.Tron -> "${formatTrxValue(params.feeLimitSun)} TRX"
    }
}

/**
 * Format [CustomFeeParams] into a list of detail lines for the
 * Network Fee card.  Returns `null` when no params are available.
 */
fun formatFeeDetail(params: CustomFeeParams?): List<String>? {
    if (params == null) return null
    return when (params) {
        is CustomFeeParams.Btc -> listOf("Fee rate: ${params.feeRateSatVb} sat/vB")
        is CustomFeeParams.Eth -> listOf(
            "Max fee: ${formatGwei(params.maxFeePerGasMilliGwei)} Gwei",
            "Priority tip: ${formatGwei(params.maxPriorityFeePerGasMilliGwei)} Gwei"
        )
        is CustomFeeParams.Tron -> listOf(
            "Fee limit: ${formatTrxValue(params.feeLimitSun)} TRX (${params.feeLimitSun} SUN)"
        )
    }
}
package com.ultrabytecoder.coinsafebox.ui.util

import kotlin.math.absoluteValue

fun formatFiat(amount: Double, currencyCode: String): String {
    if (amount > 0.0 && amount < 0.005) return "< 0.01 $currencyCode"
    val rounded = (amount * 100.0).roundHalfUp() / 100.0
    val negative = rounded < 0.0
    val absValue = rounded.absoluteValue

    val integerPart = absValue.toLong()
    val fractionalPart = ((absValue - integerPart) * 100.0).roundHalfUp().toInt()

    val sign = if (negative) "-" else ""
    return "$sign${groupThousands(integerPart)}.${fractionalPart.toString().padStart(2, '0')} $currencyCode"
}

private fun Double.roundHalfUp(): Long {
    val floor = kotlin.math.floor(this)
    return if (this - floor >= 0.5) floor.toLong() + 1 else floor.toLong()
}

private fun groupThousands(value: Long): String {
    val digits = value.toString()
    val sb = StringBuilder()
    for (i in digits.indices) {
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
        sb.append(digits[i])
    }
    return sb.toString()
}

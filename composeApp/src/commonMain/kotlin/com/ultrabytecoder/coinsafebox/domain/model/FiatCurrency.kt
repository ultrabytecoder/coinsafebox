package com.ultrabytecoder.coinsafebox.domain.model

enum class FiatCurrency(
    val code: String,
    val displayName: String,
    val usdConversionFactor: Double
) {
    USD("USD", "US Dollar", 1.00),
    EUR("EUR", "Euro", 0.85),
    GBP("GBP", "British Pound", 0.78),
    JPY("JPY", "Japanese Yen", 150.0),
    RUB("RUB", "Russian Ruble", 92.0),
    CNY("CNY", "Chinese Yuan", 7.25),
    UAH("UAH", "Ukrainian Hryvnia", 39.5);

    companion object {
        val SUPPORTED: List<FiatCurrency> = listOf(USD, EUR)

        fun fromCode(code: String?): FiatCurrency =
            entries.firstOrNull { it.code == code } ?: USD

        fun fromStored(code: String?): FiatCurrency =
            fromCode(code).let { if (it in SUPPORTED) it else USD }
    }
}

package com.ultrabytecoder.coinsafebox.domain.provider

import com.ultrabytecoder.coinsafebox.domain.model.FiatCurrency

interface FiatQuoteProvider {
    suspend fun getPrice(cryptoSymbol: String, fiat: FiatCurrency): Double
}

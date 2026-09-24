package com.ultrabytecoder.coinsafebox.domain.provider

import com.ultrabytecoder.coinsafebox.domain.model.FiatCurrency

class MockFiatQuoteProvider : FiatQuoteProvider {

    private val usdPrices: Map<String, Double> = mapOf(
        "BTC" to 100_000.0,
        "ETH" to 4_000.0,
        "TRX" to 0.20,
        "GRAM" to 8.0,
        "USDT" to 1.0,
        "USDC" to 1.0,
        "DAI" to 1.0,
        "LINK" to 18.0,
        "WETH" to 4_000.0,
        "WBTC" to 100_000.0,
        "BTT" to 0.0000008
    )

    override suspend fun getPrice(cryptoSymbol: String, fiat: FiatCurrency): Double {
        val baseUsd = usdPrices[cryptoSymbol.uppercase()] ?: 1.0
        return baseUsd * fiat.usdConversionFactor
    }
}
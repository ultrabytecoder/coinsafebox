package com.ultrabytecoder.coinsafebox.data

import com.ultrabytecoder.coinsafebox.domain.model.FiatCurrency
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteFiatQuoteProviderTest {

    private val ratesJson = """[{"src":"BTC","dest":"USD","rate":"77282.2"},{"src":"BTC","dest":"EUR","rate":"66670.5"},{"src":"ETH","dest":"USD","rate":"2173.5"},{"src":"ETH","dest":"EUR","rate":"1850.0"},{"src":"USDT","dest":"USD","rate":"0.99981"}]"""

    private fun mockRatesClient(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = ratesJson,
        shouldThrow: Boolean = false
    ): () -> HttpClient = {
        HttpClient(MockEngine) {
            engine {
                addHandler {
                    if (shouldThrow) throw IllegalStateException("network down")
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
            }
        }
    }

    @Test
    fun getPrice_returnsLiveRatesFromApi() = runTest {
        val provider = RemoteFiatQuoteProvider(NetworkConfig.testnet("k"), createClient = mockRatesClient())
        assertEquals(77282.2, provider.getPrice("BTC", FiatCurrency.USD), 1e-6)
        assertEquals(66670.5, provider.getPrice("BTC", FiatCurrency.EUR), 1e-6)
        assertEquals(1850.0, provider.getPrice("ETH", FiatCurrency.EUR), 1e-6)
        assertEquals(0.99981, provider.getPrice("USDT", FiatCurrency.USD), 1e-9)
    }

    @Test
    fun getPrice_uppercasesSymbol() = runTest {
        val provider = RemoteFiatQuoteProvider(NetworkConfig.testnet("k"), createClient = mockRatesClient())
        assertEquals(77282.2, provider.getPrice("btc", FiatCurrency.USD), 1e-6)
    }

    @Test
    fun getPrice_fallsBackToMockWhenApiReturnsError() = runTest {
        val provider = RemoteFiatQuoteProvider(
            NetworkConfig.testnet("k"),
            createClient = mockRatesClient(status = HttpStatusCode.InternalServerError, body = """{"error":"internal server error"}""")
        )
        assertEquals(100_000.0, provider.getPrice("BTC", FiatCurrency.USD), 1e-6)
    }

    @Test
    fun getPrice_fallsBackToMockWhenNetworkThrows() = runTest {
        val provider = RemoteFiatQuoteProvider(NetworkConfig.testnet("k"), createClient = mockRatesClient(shouldThrow = true))
        assertEquals(100_000.0, provider.getPrice("BTC", FiatCurrency.USD), 1e-6)
    }

    @Test
    fun getPrice_fallsBackToMockForUnknownSymbol() = runTest {
        val provider = RemoteFiatQuoteProvider(NetworkConfig.testnet("k"), createClient = mockRatesClient())
        assertEquals(1.0, provider.getPrice("DOGE", FiatCurrency.USD), 1e-9)
    }
}

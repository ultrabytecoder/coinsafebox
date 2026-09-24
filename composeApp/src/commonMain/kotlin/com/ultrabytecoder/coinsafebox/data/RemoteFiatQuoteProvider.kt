package com.ultrabytecoder.coinsafebox.data

import com.ultrabytecoder.coinsafebox.domain.model.FiatCurrency
import com.ultrabytecoder.coinsafebox.domain.provider.FiatQuoteProvider
import com.ultrabytecoder.coinsafebox.domain.provider.MockFiatQuoteProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RemoteFiatQuoteProvider(
    private val networkConfig: NetworkConfig,
    createClient: () -> HttpClient = { HttpClient() },
    private val ttlMillis: Long = 30_000,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val fallback: FiatQuoteProvider = MockFiatQuoteProvider(),
) : FiatQuoteProvider {

    private val client: HttpClient = createClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private var cache: Map<String, Double> = emptyMap()
    private var lastSuccessAt: Long = 0L
    private var lastAttemptAt: Long = 0L

    override suspend fun getPrice(cryptoSymbol: String, fiat: FiatCurrency): Double {
        val snapshot = ensureLoaded()
        return snapshot["${cryptoSymbol.uppercase()}:${fiat.code}"]
            ?: fallback.getPrice(cryptoSymbol, fiat)
    }

    private suspend fun ensureLoaded(): Map<String, Double> = lock.withLock {
        val now = clock()
        // On success: full TTL before refetching.
        // On failure: short backoff (5s) so a transient blip doesn't suppress
        // retries for the full TTL.
        val effectiveTtl = if (cache.isEmpty()) FAILURE_BACKOFF_MS else ttlMillis
        if (now - lastAttemptAt < effectiveTtl) return@withLock cache

        lastAttemptAt = now
        try {
            val response = client.get("${networkConfig.exchangeRateApiBase}/api/v1/rates")
            if (response.status == HttpStatusCode.OK) {
                val parsed = json.parseToJsonElement(response.body<String>()).jsonArray
                    .mapNotNull { element ->
                        val obj = element.jsonObject
                        val src = obj["src"]?.jsonPrimitive?.content
                        val dest = obj["dest"]?.jsonPrimitive?.content
                        val rate = obj["rate"]?.jsonPrimitive?.content?.toDoubleOrNull()
                        if (src != null && dest != null && rate != null) "$src:$dest" to rate else null
                    }
                    .toMap()
                if (parsed.isNotEmpty()) {
                    cache = parsed
                    lastSuccessAt = now
                }
            }
        } catch (e: Exception) {
            println("RemoteFiatQuoteProvider: fetch failed — ${e.message}")
        }
        cache
    }

    companion object {
        private const val FAILURE_BACKOFF_MS = 5_000L
    }
}

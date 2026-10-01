package com.ultrabytecoder.coinsafebox.providers

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.providers.ton.TonProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TON is the one chain whose `estimateFee` genuinely needs the private key
 * (it signs a BOC that toncenter executes). In read-only mode it must fall
 * back to a seqno-dependent OVERESTIMATE with no key derivation:
 * 0.05 TON when `seqno == 0` (wallet not yet deployed) and 0.015 TON otherwise.
 */
class TonProviderReadOnlyEstimateFeeTest {

    companion object {
        private const val ACCOUNT_ID = "test-account-id"
        private val AMOUNT = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))
    }

    private val networkConfig = NetworkConfig.testnet("test-api-key")

    private fun tonAccount(address: String) = AccountInfo(
        id = ACCOUNT_ID, walletId = 1, name = "Test", amount = "0",
        type = AccountType.Ton(), symbol = "GRAM", address = address, accountIndex = 0,
        derivationPath = "m/44'/607'/0'"
    )

    private fun mockClient(seqno: Int): () -> HttpClient = {
        HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = """{"ok":true,"result":{"wallet":true,"seqno":$seqno}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
            }
        }
    }

    private fun readOnlyProvider(seqno: Int): TonProvider = TonProvider(
        null,
        FakeAccountRepository(mapOf(ACCOUNT_ID to tonAccount("UQfakeTonAddress000000000000000000000000000000"))),
        JsonObject(emptyMap()),
        networkConfig,
        FakeTransactionRepository(),
        mockClient(seqno)
    )

    @Test
    fun estimateFee_undeployedWalletReturnsDeployFallback() = runTest {
        val provider = readOnlyProvider(seqno = 0)
        val fee = provider.estimateFee(ACCOUNT_ID, AMOUNT, null, null)
        val expected = BigDecimal.fromLong(50_000_000L).divide(BigDecimal.fromLong(1_000_000_000L)) // 0.05 TON
        assertEquals(expected.toPlainString(), fee.totalCost.toPlainString())
    }

    @Test
    fun estimateFee_deployedWalletReturnsTransferFallback() = runTest {
        val provider = readOnlyProvider(seqno = 7)
        val fee = provider.estimateFee(ACCOUNT_ID, AMOUNT, null, null)
        val expected = BigDecimal.fromLong(15_000_000L).divide(BigDecimal.fromLong(1_000_000_000L)) // 0.015 TON
        assertEquals(expected.toPlainString(), fee.totalCost.toPlainString())
    }

    @Test
    fun isReadOnly_isTrueWhenNoSeed() {
        assertEquals(true, readOnlyProvider(seqno = 0).isReadOnly)
    }
}

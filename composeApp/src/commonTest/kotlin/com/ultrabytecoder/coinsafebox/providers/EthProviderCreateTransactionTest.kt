package com.ultrabytecoder.coinsafebox.providers

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.secp256k1.Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EthProviderCreateTransactionTest {

    companion object {
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val ACCOUNT_ID = "test-account-id"
    }

    private fun mockEthFactory(
        nonce: Long = 1,
        gasTipCap: Long = 2_000_000_000,
        baseFee: Long = 10_000_000_000
    ): () -> HttpClient {
        val responses = listOf(
            """{"jsonrpc":"2.0","result":"0x${nonce.toString(16)}","id":1}""",
            """{"jsonrpc":"2.0","result":"0x${gasTipCap.toString(16)}","id":1}""",
            """{"jsonrpc":"2.0","result":{"baseFeePerGas":"0x${baseFee.toString(16)}","number":"0x1"},"id":1}"""
        )
        var index = 0
        return {
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        val response = responses[index % responses.size]
                        index++
                        respond(
                            content = response,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            }
        }
    }

    private fun createProvider(createClient: () -> HttpClient = mockEthFactory()): EthProvider {
        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))
        val account = AccountInfo(ACCOUNT_ID, 1, "Test", "0", AccountType.Eth, "ETH", null, 0, "m/44'/60'/0'/0/0")
        return EthProvider(masterKey, FakeAccountRepository(mapOf(ACCOUNT_ID to account)), JsonObject(emptyMap()), NetworkConfig.testnet("test-api-key"), createClient, FakeTransactionRepository())
    }

    @Test
    fun createTransaction_returnsHexWithOxPrefix() = runTest {
        val provider = createProvider()
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        val result = provider.createTransaction("0x9858effd232b4033e47d90003d41ec34ecaeda94", amount, ACCOUNT_ID)

        assertTrue(result.startsWith("0x"), "ETH raw transaction should start with 0x")
        assertTrue(result.length > 10, "Transaction should be a substantial hex string")
    }

    @Test
    fun createTransaction_isDeterministic() = runTest {
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))
        val addr = "0x9858effd232b4033e47d90003d41ec34ecaeda94"

        val provider1 = createProvider(mockEthFactory(nonce = 5, gasTipCap = 1_000_000_000, baseFee = 5_000_000_000))
        val result1 = provider1.createTransaction(addr, amount, ACCOUNT_ID)

        val provider2 = createProvider(mockEthFactory(nonce = 5, gasTipCap = 1_000_000_000, baseFee = 5_000_000_000))
        val result2 = provider2.createTransaction(addr, amount, ACCOUNT_ID)

        assertEquals(result1, result2)
    }

    @Test
    fun createTransaction_differentAmountsProduceDifferentTransactions() = runTest {
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        val provider1 = createProvider(mockEthFactory(nonce = 1))
        val result1 = provider1.createTransaction("0x9858effd232b4033e47d90003d41ec34ecaeda94", amount, ACCOUNT_ID)

        val provider2 = createProvider(mockEthFactory(nonce = 1))
        val result2 = provider2.createTransaction("0x9858effd232b4033e47d90003d41ec34ecaeda94", amount.divide(BigDecimal.fromLong(2)), ACCOUNT_ID)

        assertTrue(result1 != result2, "Different amounts should produce different transactions")
    }

    @Test
    fun createTransaction_containsOnlyValidHexAfterPrefix() = runTest {
        val provider = createProvider()
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        val result = provider.createTransaction("0x9858effd232b4033e47d90003d41ec34ecaeda94", amount, ACCOUNT_ID)
        val hex = result.removePrefix("0x")

        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' }, "Should be valid lowercase hex")
    }

    @Test
    fun createTransaction_producesEip1559Type02Envelope() = runTest {
        val provider = createProvider()
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        val result = provider.createTransaction("0x9858effd232b4033e47d90003d41ec34ecaeda94", amount, ACCOUNT_ID)

        assertTrue(result.startsWith("0x02"), "EIP-1559 transaction should start with 0x02 type prefix")
    }

    @Test
    fun createTransaction_throwsWhenAmountExceedsWeiMax() = runTest {
        val provider = createProvider()
        // 10 ETH = 10^19 wei, exceeds Long.MAX_VALUE (9.22 × 10^18)
        val hugeAmount = BigDecimal.fromLong(10)

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            provider.createTransaction("0x9858effd232b4033e47d90003d41ec34ecaeda94", hugeAmount, ACCOUNT_ID)
        }
    }
}

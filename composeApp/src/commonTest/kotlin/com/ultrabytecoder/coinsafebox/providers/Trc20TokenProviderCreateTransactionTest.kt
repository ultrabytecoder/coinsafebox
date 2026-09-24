package com.ultrabytecoder.coinsafebox.providers

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.providers.tron.Trc20TokenProvider
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.secp256k1.Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Trc20TokenProviderCreateTransactionTest {

    companion object {
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val CONTRACT_ADDRESS = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
        private const val DECIMALS_RESPONSE =
            """{"constant_result":["0000000000000000000000000000000000000000000000000000000000000006"]}"""
        private const val TRANSFER_RESPONSE = """{"result":true,"transaction":{"txID":"bb000000000000000000000000000000000000000000000000000000000000bb","raw_data":{"ref_block_bytes":"0001"},"raw_data_hex":"0a02"}}"""
        private const val ACCOUNT_ID = "test-account-id"
        private const val DEST_ACCOUNT_ID = "dest-account"
    }

    private fun createMockFactory(): () -> HttpClient {
        return {
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val bodyText = (request.body as? TextContent)?.text ?: ""
                        val response = when {
                            bodyText.contains("decimals()") -> DECIMALS_RESPONSE
                            else -> TRANSFER_RESPONSE
                        }
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

    private fun createErrorFactory(): () -> HttpClient {
        return {
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val bodyText = (request.body as? TextContent)?.text ?: ""
                        val response = when {
                            bodyText.contains("decimals()") -> DECIMALS_RESPONSE
                            else -> """{"Error":"contract validate error"}"""
                        }
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

    private fun createProvider(createClient: () -> HttpClient = createMockFactory()): Trc20TokenProvider {
        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))
        val account = AccountInfo(ACCOUNT_ID, 1, "Test", "0", AccountType.Trx, "TRX", null, 0, "m/44'/195'/0'/0/0")
        val destAccount = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Trx, "TRX", null, 1, "m/44'/195'/1'/0/0")
        return Trc20TokenProvider(
            masterKey, FakeAccountRepository(mapOf(ACCOUNT_ID to account, DEST_ACCOUNT_ID to destAccount)),
            JsonObject(mapOf("tokenAddress" to JsonPrimitive(CONTRACT_ADDRESS))),
            NetworkConfig.testnet("test-api-key"),
            FakeTransactionRepository(),
            createClient
        )
    }

    @Test
    fun createTransaction_returnsValidJsonWithRequiredFields() = runTest {
        val provider = createProvider()
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        val amount = BigDecimal.fromLong(100)

        val result = provider.createTransaction(destAddress, amount, ACCOUNT_ID)

        val json = Json.parseToJsonElement(result).jsonObject
        assertNotNull(json["txid"], "Should contain txid")
        assertNotNull(json["raw_data"], "Should contain raw_data")
        assertNotNull(json["raw_data_hex"], "Should contain raw_data_hex")
        assertNotNull(json["signature"], "Should contain signature")
    }

    @Test
    fun createTransaction_containsExpectedTxid() = runTest {
        val provider = createProvider()
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        val amount = BigDecimal.fromLong(100)

        val result = provider.createTransaction(destAddress, amount, ACCOUNT_ID)

        val json = Json.parseToJsonElement(result).jsonObject
        assertEquals(
            "bb000000000000000000000000000000000000000000000000000000000000bb",
            json["txid"].toString().replace("\"", "")
        )
    }

    @Test
    fun createTransaction_signatureIsValidHex() = runTest {
        val provider = createProvider()
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        val amount = BigDecimal.fromLong(100)

        val result = provider.createTransaction(destAddress, amount, ACCOUNT_ID)

        val json = Json.parseToJsonElement(result).jsonObject
        val signature = json["signature"].toString()
            .trim('[').trim(']').trim('"').replace("\"", "")
        assertTrue(signature.isNotEmpty(), "Signature should not be empty")
        assertTrue(signature.all { it in '0'..'9' || it in 'a'..'f' }, "Signature should be valid hex")
    }

    @Test
    fun createTransaction_throwsWhenApiReturnsError() = runTest {
        val provider = createProvider(createErrorFactory())
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)

        assertFailsWith<IllegalStateException> {
            provider.createTransaction(destAddress, BigDecimal.fromLong(100), ACCOUNT_ID)
        }
    }

    @Test
    fun createTransaction_isDeterministic() = runTest {
        val provider = createProvider()
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        val amount = BigDecimal.fromLong(100)

        val result1 = provider.createTransaction(destAddress, amount, ACCOUNT_ID)
        val result2 = provider.createTransaction(destAddress, amount, ACCOUNT_ID)

        assertEquals(result1, result2)
    }
}

package com.ultrabytecoder.coinsafebox.providers

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams
import com.ultrabytecoder.coinsafebox.domain.model.TransactionDirection
import com.ultrabytecoder.coinsafebox.domain.model.TransactionStatus
import com.ultrabytecoder.coinsafebox.providers.tron.Trc20TokenProvider
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.secp256k1.Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Trc20TokenProviderSyncTest {

    companion object {
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val CONTRACT_ADDRESS = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
        private const val ACCOUNT_ID = "test-account-id"
        private const val DEST_ACCOUNT_ID = "dest-account"

        // Decimals response for TRC20 token
        private const val DECIMALS_RESPONSE =
            """{"constant_result":["0000000000000000000000000000000000000000000000000000000000000006"]}"""

        // Balance response for TRC20 (triggerSmartContract balanceOf) -- 0 balance
        private const val BALANCE_RESPONSE =
            """{"constant_result":["0000000000000000000000000000000000000000000000000000000000000000"]}"""

        // Two TRC20 transfers: one outgoing, one incoming
        // __WALLET_ADDRESS__ will be replaced at runtime with actual derived base58 address
        private const val TRC20_TRANSACTIONS_TEMPLATE = """
        {
            "data": [
                {
                    "transaction_id": "aaa1110000000000000000000000000000000000000000000000000000000000",
                    "block_timestamp": 1700000000000,
                    "from": "__WALLET_ADDRESS__",
                    "to": "TNPeeaaFBmJcrLnKbPqL8qSbKQ3MQ2eFJj",
                    "type": "Transfer",
                    "value": "1000000",
                    "token_info": {
                        "symbol": "USDT",
                        "decimals": 6,
                        "address": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
                    },
                    "finalResult": "SUCCESS"
                },
                {
                    "transaction_id": "bbb2220000000000000000000000000000000000000000000000000000000000",
                    "block_timestamp": 1700000001000,
                    "from": "TNPeeaaFBmJcrLnKbPqL8qSbKQ3MQ2eFJj",
                    "to": "__WALLET_ADDRESS__",
                    "type": "Transfer",
                    "value": "2500000",
                    "token_info": {
                        "symbol": "USDT",
                        "decimals": 6,
                        "address": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
                    },
                    "finalResult": "REVERT"
                }
            ],
            "meta": {"fingerprint": "fp123"}
        }
        """

        private const val EMPTY_TRANSACTIONS_RESPONSE = """
        {"data":[],"meta":{"fingerprint":""}}
        """

        // Transfer from a different token contract -- should be filtered out by contract_address URL param
        private const val WRONG_CONTRACT_TRANSACTIONS_TEMPLATE = """
        {
            "data": [
                {
                    "transaction_id": "ccc3330000000000000000000000000000000000000000000000000000000000",
                    "block_timestamp": 1700000002000,
                    "from": "__WALLET_ADDRESS__",
                    "to": "TNPeeaaFBmJcrLnKbPqL8qSbKQ3MQ2eFJj",
                    "type": "Transfer",
                    "value": "500000",
                    "token_info": {
                        "symbol": "JST",
                        "decimals": 6,
                        "address": "TCFLL5dp5FxfDqAVEDr5W8aOhnvCoRZoh9"
                    },
                    "finalResult": "SUCCESS"
                }
            ],
            "meta": {"fingerprint": "fp456"}
        }
        """
    }

    private fun testAccount(index: Long = 0) = AccountInfo(
        id = ACCOUNT_ID, walletId = 1, name = "Test", amount = "0",
        type = AccountType.Trx, symbol = "TRX", address = null, accountIndex = index,
        derivationPath = "m/44'/195'/$index'/0/0"
    )

    private fun createMockClientFactory(
        transactionsResponse: String? = null
    ): () -> HttpClient {
        val txResponse = transactionsResponse
        var triggerCount = 0
        return {
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when {
                            request.url.encodedPath.contains("/transactions/trc20") ->
                                respond(
                                    content = txResponse ?: EMPTY_TRANSACTIONS_RESPONSE,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                            request.url.encodedPath.contains("/wallet/triggersmartcontract") -> {
                                // First call is balanceOf, second is decimals
                                val response = if (triggerCount == 0) BALANCE_RESPONSE else DECIMALS_RESPONSE
                                triggerCount++
                                respond(
                                    content = response,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                            }
                            else ->
                                respond(
                                    content = "{}",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                        }
                    }
                }
            }
        }
    }

    private fun createProvider(
        account: AccountInfo = testAccount(),
        fakeTransactionRepo: FakeTransactionRepository = FakeTransactionRepository(),
        createClient: () -> HttpClient = createMockClientFactory()
    ): Trc20TokenProvider {
        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))
        val destAccount = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Trx, "TRX", null, 1, "m/44'/195'/1'/0/0")
        return Trc20TokenProvider(
            masterKey,
            FakeAccountRepository(mapOf(ACCOUNT_ID to account, DEST_ACCOUNT_ID to destAccount)),
            JsonObject(mapOf("tokenAddress" to JsonPrimitive(CONTRACT_ADDRESS))),
            NetworkConfig.testnet("test-api-key"),
            fakeTransactionRepo,
            createClient
        )
    }

    /**
     * Derive the wallet address and replace __WALLET_ADDRESS__ placeholder in template.
     * The provider must be created first to derive the address from the seed.
     */
    private suspend fun resolveResponse(provider: Trc20TokenProvider, template: String): String {
        val walletAddress = provider.getAddress(ACCOUNT_ID)
        return template.replace("__WALLET_ADDRESS__", walletAddress)
    }

    @Test
    fun sync_fetchesAndPersistsTrc20Transactions() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, TRC20_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        assertEquals(2, transactions.size, "Should parse and persist 2 TRC20 transactions")
    }

    @Test
    fun directionOutgoing_whenFromMatchesWallet() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, TRC20_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val outgoingTx = transactions.find {
            it.txHash == "aaa1110000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(outgoingTx, "Should find outgoing transaction")
        assertEquals(TransactionDirection.OUTGOING, outgoingTx.direction,
            "Transaction where from=wallet should be OUTGOING")
    }

    @Test
    fun directionIncoming_whenToMatchesWallet() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, TRC20_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val incomingTx = transactions.find {
            it.txHash == "bbb2220000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(incomingTx, "Should find incoming transaction")
        assertEquals(TransactionDirection.INCOMING, incomingTx.direction,
            "Transaction where to=wallet should be INCOMING")
    }

    @Test
    fun amountStoredAsRawValueString() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, TRC20_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val outgoingTx = transactions.find {
            it.txHash == "aaa1110000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(outgoingTx)
        assertEquals("1000000", outgoingTx.amount,
            "Amount should be stored as raw value string without normalization")
    }

    @Test
    fun chainDataContainsTokenMetadata() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, TRC20_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val tx = transactions.first()
        assertNotNull(tx.chainData, "chainData should not be null")
        assertTrue(tx.chainData!!.contains("\"symbol\":\"USDT\""),
            "chainData should contain symbol")
        assertTrue(tx.chainData!!.contains("\"decimals\":6"),
            "chainData should contain decimals")
        assertTrue(tx.chainData!!.contains("\"tokenAddress\":\"TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t\""),
            "chainData should contain tokenAddress")
    }

    @Test
    fun statusMapping_successToConfirmed() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, TRC20_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val successTx = transactions.find {
            it.txHash == "aaa1110000000000000000000000000000000000000000000000000000000000"
        }
        assertEquals(TransactionStatus.CONFIRMED, successTx?.status,
            "finalResult SUCCESS should map to CONFIRMED")

        val revertTx = transactions.find {
            it.txHash == "bbb2220000000000000000000000000000000000000000000000000000000000"
        }
        assertEquals(TransactionStatus.FAILED, revertTx?.status,
            "finalResult REVERT should map to FAILED")
    }

    @Test
    fun emptyDataArray_returnsEmptyList() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = EMPTY_TRANSACTIONS_RESPONSE)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        assertEquals(0, transactions.size,
            "Empty data array should result in no transactions")
    }

    @Test
    fun onlyTransfersMatchingContractAddressIncluded() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, WRONG_CONTRACT_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        assertEquals(0, transactions.size,
            "Transfers from other token contracts should be filtered out")
    }

    @Test
    fun feePresets_fallsBackWhenDecimalsConstantResultMissing() = runTest {
        val errorResponse = """{"result":{"result":false,"code":"CONTRACT_VALIDATE_ERROR","message":"636f6e74726163742076616c6964617465206572726f72"},"energy_used":0}"""
        val energyResponse = """{"energy_used":2000000}"""
        val transactionsResponse = """
        {
            "data": [
                {
                    "transaction_id": "eee5550000000000000000000000000000000000000000000000000000000000",
                    "block_timestamp": 1700000004000,
                    "from": "TNPeeaaFBmJcrLnKbPqL8qSbKQ3MQ2eFJj",
                    "to": "TNPeeaaFBmJcrLnKbPqL8qSbKQ3MQ2eFJj",
                    "value": "1000000",
                    "token_info": {
                        "symbol": "USDT",
                        "decimals": 6,
                        "address": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
                    },
                    "finalResult": "SUCCESS"
                }
            ],
            "meta": {"fingerprint": "fp999"}
        }
        """.trimIndent()

        var triggerCount = 0
        val fallbackFactory: () -> HttpClient = {
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when {
                            request.url.encodedPath.contains("/transactions/trc20") ->
                                respond(
                                    content = transactionsResponse,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                            request.url.encodedPath.contains("/wallet/triggersmartcontract") -> {
                                val response = if (triggerCount == 0) errorResponse else energyResponse
                                triggerCount++
                                respond(
                                    content = response,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                            }
                            else ->
                                respond(
                                    content = "{}",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                        }
                    }
                }
            }
        }

        val provider = createProvider(createClient = fallbackFactory)
        val presets = provider.feePresets(ACCOUNT_ID)

        val slow = (presets.slow as CustomFeeParams.Tron).feeLimitSun
        val medium = (presets.medium as CustomFeeParams.Tron).feeLimitSun
        val fast = (presets.fast as CustomFeeParams.Tron).feeLimitSun
        assertEquals(2_200_000L, slow, "feePresets should work via transactions fallback when constant_result missing")
        assertEquals(2_600_000L, medium)
        assertEquals(3_000_000L, fast)
    }

    private fun createEnergyMockClientFactory(energyUsed: Long = 2_000_000L): () -> HttpClient {
        var triggerCount = 0
        return {
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        when {
                            it.url.encodedPath.contains("/wallet/triggersmartcontract") -> {
                                val response = if (triggerCount == 0) {
                                    DECIMALS_RESPONSE
                                } else {
                                    """{"energy_used":$energyUsed}"""
                                }
                                triggerCount++
                                respond(
                                    content = response,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                            }
                            else ->
                                respond(
                                    content = "{}",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun feePresets_derivesFromSimulatedEnergy() = runTest {
        val provider = createProvider(createClient = createEnergyMockClientFactory(energyUsed = 2_000_000L))

        val presets = provider.feePresets(ACCOUNT_ID)

        val slow = (presets.slow as CustomFeeParams.Tron).feeLimitSun
        val medium = (presets.medium as CustomFeeParams.Tron).feeLimitSun
        val fast = (presets.fast as CustomFeeParams.Tron).feeLimitSun
        assertEquals(2_200_000L, slow, "slow should be 1.1x of simulated energy")
        assertEquals(2_600_000L, medium, "medium should be 1.3x of simulated energy")
        assertEquals(3_000_000L, fast, "fast should be 1.5x of simulated energy")
    }

    @Test
    fun feePresets_clampsToBounds() = runTest {
        val smallProvider = createProvider(createClient = createEnergyMockClientFactory(energyUsed = 10L))
        val smallPresets = smallProvider.feePresets(ACCOUNT_ID)
        assertEquals(1_000_000L, (smallPresets.slow as CustomFeeParams.Tron).feeLimitSun)
        assertEquals(1_000_000L, (smallPresets.medium as CustomFeeParams.Tron).feeLimitSun)
        assertEquals(1_000_000L, (smallPresets.fast as CustomFeeParams.Tron).feeLimitSun)

        val bigProvider = createProvider(createClient = createEnergyMockClientFactory(energyUsed = 100_000_000_000L))
        val bigPresets = bigProvider.feePresets(ACCOUNT_ID)
        assertEquals(100_000_000L, (bigPresets.slow as CustomFeeParams.Tron).feeLimitSun)
        assertEquals(100_000_000L, (bigPresets.medium as CustomFeeParams.Tron).feeLimitSun)
        assertEquals(100_000_000L, (bigPresets.fast as CustomFeeParams.Tron).feeLimitSun)
    }

    @Test
    fun feePresets_cachesEnergyEstimate() = runTest {
        var triggerCount = 0
        val countingFactory: () -> HttpClient = {
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        when {
                            it.url.encodedPath.contains("/wallet/triggersmartcontract") -> {
                                val response = if (triggerCount == 0) DECIMALS_RESPONSE else """{"energy_used":2000000}"""
                                triggerCount++
                                respond(
                                    content = response,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                            }
                            else ->
                                respond(
                                    content = "{}",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                        }
                    }
                }
            }
        }
        val provider = createProvider(createClient = countingFactory)

        provider.feePresets(ACCOUNT_ID)
        provider.feePresets(ACCOUNT_ID)

        assertEquals(2, triggerCount, "decimals + one energy simulation; second feePresets call should hit cache")
    }
}

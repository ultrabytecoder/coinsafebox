package com.ultrabytecoder.coinsafebox.providers

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.TransactionDirection
import com.ultrabytecoder.coinsafebox.domain.model.TransactionStatus
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.secp256k1.Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Erc20TokenProviderSyncTest {

    companion object {
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val CONTRACT_ADDRESS = "0xdAC17F970D19C5a1DB0c2D89f1E3dC26cF1aA54D"
        private const val ACCOUNT_ID = "test-account-id"

        // ERC20 RPC balanceOf response (0 balance)
        private const val BALANCE_OF_RESPONSE =
            """{"jsonrpc":"2.0","id":1,"result":"0x0000000000000000000000000000000000000000000000000000000000000000"}"""

        // ERC20 decimals response (6 decimals for USDT)
        private const val DECIMALS_RESPONSE =
            """{"jsonrpc":"2.0","id":1,"result":"0x0000000000000000000000000000000000000000000000000000000000000006"}"""

        // Two ERC20 transfers: one outgoing, one incoming
        private const val ERC20_TRANSACTIONS_TEMPLATE = """
        {
            "status": "1",
            "message": "OK",
            "result": [
                {
                    "blockNumber": "19000000",
                    "timeStamp": "1700000000",
                    "hash": "0xaaa1110000000000000000000000000000000000000000000000000000000000",
                    "from": "__WALLET_ADDRESS__",
                    "to": "0xdead0000000000000000000000000000000000000",
                    "value": "1000000",
                    "tokenName": "Tether USD",
                    "tokenSymbol": "USDT",
                    "tokenDecimal": "6",
                    "contractAddress": "0xdAC17F970D19C5a1DB0c2D89f1E3dC26cF1aA54D",
                    "gas": "60000",
                    "gasPrice": "20000000000",
                    "gasUsed": "55000",
                    "isError": "0",
                    "txreceipt_status": "1"
                },
                {
                    "blockNumber": "19000001",
                    "timeStamp": "1700000001",
                    "hash": "0xbbb2220000000000000000000000000000000000000000000000000000000000",
                    "from": "0xdead0000000000000000000000000000000000000",
                    "to": "__WALLET_ADDRESS__",
                    "value": "2500000",
                    "tokenName": "Tether USD",
                    "tokenSymbol": "USDT",
                    "tokenDecimal": "6",
                    "contractAddress": "0xdAC17F970D19C5a1DB0c2D89f1E3dC26cF1aA54D",
                    "gas": "60000",
                    "gasPrice": "20000000000",
                    "gasUsed": "55000",
                    "isError": "0",
                    "txreceipt_status": "1"
                }
            ]
        }
        """

        private const val EMPTY_RESULT_RESPONSE = """
        {
            "status": "0",
            "message": "No transactions found",
            "result": []
        }
        """
    }

    private fun testAccount(index: Long = 0) = AccountInfo(
        id = ACCOUNT_ID, walletId = 1, name = "Test", amount = "0",
        type = AccountType.Erc20(CONTRACT_ADDRESS), symbol = "USDC", address = null, accountIndex = index,
        derivationPath = "m/44'/60'/$index'/0/0"
    )

    private fun createMockClientFactory(
        transactionsResponse: String? = null
    ): () -> HttpClient {
        val txResponse = transactionsResponse
        var rpcCallCount = 0
        return {
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val url = request.url.toString()
                        when {
                            url.contains("etherscan") ->
                                respond(
                                    content = txResponse ?: EMPTY_RESULT_RESPONSE,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json")
                                )
                            // RPC calls: balanceOf then decimals for balance(), then possibly more for sync
                            else -> {
                                // First RPC call = balanceOf, second = decimals
                                val rpcResponse = if (rpcCallCount == 0) BALANCE_OF_RESPONSE else DECIMALS_RESPONSE
                                rpcCallCount++
                                respond(
                                    content = rpcResponse,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json")
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createProvider(
        account: AccountInfo = testAccount(),
        params: JsonObject = JsonObject(mapOf("tokenAddress" to JsonPrimitive(CONTRACT_ADDRESS))),
        fakeTransactionRepo: FakeTransactionRepository = FakeTransactionRepository(),
        createClient: () -> HttpClient = createMockClientFactory()
    ): Erc20TokenProvider {
        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))
        return Erc20TokenProvider(
            masterKey,
            FakeAccountRepository(mapOf(ACCOUNT_ID to account)),
            params,
            NetworkConfig.testnet("test-api-key"),
            createClient,
            fakeTransactionRepo
        )
    }

    private suspend fun resolveResponse(provider: Erc20TokenProvider, template: String): String {
        val walletAddress = provider.getAddress(ACCOUNT_ID)
        return template.replace("__WALLET_ADDRESS__", walletAddress)
    }

    @Test
    fun sync_fetchesErc20TransfersAndPersists() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, ERC20_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        assertEquals(2, transactions.size, "Should parse and persist 2 ERC20 transfers")
    }

    @Test
    fun directionOutgoing_whenFromMatchesWallet() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, ERC20_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val outgoingTx = transactions.find {
            it.txHash == "0xaaa1110000000000000000000000000000000000000000000000000000000000"
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
        val response = resolveResponse(provider, ERC20_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val incomingTx = transactions.find {
            it.txHash == "0xbbb2220000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(incomingTx, "Should find incoming transaction")
        assertEquals(TransactionDirection.INCOMING, incomingTx.direction,
            "Transaction where to=wallet should be INCOMING")
    }

    @Test
    fun feeCalculatedAsGasUsedTimesGasPrice() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, ERC20_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val tx = transactions.first()
        // gasUsed=55000, gasPrice=20000000000 -> 1100000000000000
        assertEquals("1100000000000000", tx.fee,
            "Fee should be gasUsed * gasPrice in wei")
    }

    @Test
    fun amountStoredAsRawTokenSmallestUnit() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, ERC20_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val outgoingTx = transactions.find {
            it.txHash == "0xaaa1110000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(outgoingTx)
        // value="1000000" is 1 USDT (6 decimals) -- stored raw, NOT divided by 10^6
        assertEquals("1000000", outgoingTx.amount,
            "Amount should be stored as raw token smallest unit string without normalization")
    }

    @Test
    fun chainDataContainsTokenMetadata() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, ERC20_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val tx = transactions.first()
        assertNotNull(tx.chainData, "chainData should not be null")
        assertTrue(tx.chainData!!.contains("\"tokenSymbol\":\"USDT\""),
            "chainData should contain tokenSymbol")
        assertTrue(tx.chainData!!.contains("\"tokenName\":\"Tether USD\""),
            "chainData should contain tokenName")
        assertTrue(tx.chainData!!.contains("\"tokenDecimal\":6"),
            "chainData should contain tokenDecimal")
        assertTrue(tx.chainData!!.contains("\"contractAddress\":\"0xdAC17F970D19C5a1DB0c2D89f1E3dC26cF1aA54D\""),
            "chainData should contain contractAddress")
    }

    @Test
    fun contractAddressFilterSentToEtherscan() = runTest {
        var capturedUrl = ""
        val fakeTransactionRepo = FakeTransactionRepository()
        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))

        val provider = Erc20TokenProvider(
            masterKey,
            FakeAccountRepository(mapOf(ACCOUNT_ID to testAccount())),
            JsonObject(mapOf("tokenAddress" to JsonPrimitive(CONTRACT_ADDRESS))),
            NetworkConfig.testnet("test-api-key"),
            {
                HttpClient(MockEngine) {
                    engine {
                        addHandler { request ->
                            val url = request.url.toString()
                            if (url.contains("etherscan")) {
                                capturedUrl = url
                                respond(
                                    content = EMPTY_RESULT_RESPONSE,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json")
                                )
                            } else {
                                respond(
                                    content = BALANCE_OF_RESPONSE,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json")
                                )
                            }
                        }
                    }
                }
            },
            fakeTransactionRepo
        )

        provider.sync(ACCOUNT_ID)

        assertTrue(capturedUrl.contains("contractaddress="),
            "Etherscan URL should contain contractaddress parameter")
        assertTrue(capturedUrl.contains("action=tokentx"),
            "Etherscan URL should contain action=tokentx")
    }

    @Test
    fun timestampConvertedFromSecondsToMilliseconds() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, ERC20_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val tx = transactions.find {
            it.txHash == "0xaaa1110000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(tx)
        assertEquals(1700000000000L, tx.timestamp,
            "Timestamp should be converted from seconds to milliseconds")
    }

    @Test
    fun incrementalSyncUsesStartblockFromParams() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        var capturedUrl = ""
        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))

        val paramsWithBlock = JsonObject(mapOf(
            "tokenAddress" to JsonPrimitive(CONTRACT_ADDRESS),
            "lastSyncBlock" to JsonPrimitive(18999)
        ))
        val provider = Erc20TokenProvider(
            masterKey,
            FakeAccountRepository(mapOf(ACCOUNT_ID to testAccount())),
            paramsWithBlock,
            NetworkConfig.testnet("test-api-key"),
            {
                HttpClient(MockEngine) {
                    engine {
                        addHandler { request ->
                            val url = request.url.toString()
                            if (url.contains("etherscan")) {
                                capturedUrl = url
                                respond(
                                    content = EMPTY_RESULT_RESPONSE,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json")
                                )
                            } else {
                                respond(
                                    content = BALANCE_OF_RESPONSE,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json")
                                )
                            }
                        }
                    }
                }
            },
            fakeTransactionRepo
        )

        provider.sync(ACCOUNT_ID, SyncMode.NORMAL)

        assertTrue(capturedUrl.contains("startblock=18999"),
            "URL should contain startblock from params lastSyncBlock")
    }

    @Test
    fun etherscanStatusZeroWithEmptyResult_returnsEmptyList() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = EMPTY_RESULT_RESPONSE)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        assertEquals(0, transactions.size,
            "Etherscan status=0 with empty result should not crash and return no transactions")
    }
}

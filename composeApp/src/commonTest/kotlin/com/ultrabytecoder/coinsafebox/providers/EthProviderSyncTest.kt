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

class EthProviderSyncTest {

    companion object {
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val ACCOUNT_ID = "test-account-id"

        // ETH RPC eth_getBalance response
        private const val BALANCE_RESPONSE =
            """{"jsonrpc":"2.0","id":1,"result":"0x0de0b6b3a7640000"}"""

        // Two ETH transactions: one outgoing (from=wallet), one incoming (to=wallet)
        private const val ETH_TRANSACTIONS_TEMPLATE = """
        {
            "status": "1",
            "message": "OK",
            "result": [
                {
                    "blockNumber": "19000000",
                    "timeStamp": "1700000000",
                    "hash": "0xabc1230000000000000000000000000000000000000000000000000000000000",
                    "from": "__WALLET_ADDRESS__",
                    "to": "0xdead0000000000000000000000000000000000000",
                    "value": "1000000000000000000",
                    "gas": "21000",
                    "gasPrice": "20000000000",
                    "gasUsed": "21000",
                    "isError": "0",
                    "txreceipt_status": "1",
                    "confirmations": "12345"
                },
                {
                    "blockNumber": "19000001",
                    "timeStamp": "1700000001",
                    "hash": "0xdef4560000000000000000000000000000000000000000000000000000000000",
                    "from": "0xdead0000000000000000000000000000000000000",
                    "to": "__WALLET_ADDRESS__",
                    "value": "2000000000000000000",
                    "gas": "21000",
                    "gasPrice": "20000000000",
                    "gasUsed": "21000",
                    "isError": "0",
                    "txreceipt_status": "1",
                    "confirmations": "12344"
                }
            ]
        }
        """

        // Transaction with isError="1" (failed tx) and txreceipt_status="0"
        private const val FAILED_TX_RESPONSE = """
        {
            "status": "1",
            "message": "OK",
            "result": [
                {
                    "blockNumber": "19000002",
                    "timeStamp": "1700000002",
                    "hash": "0xfail000000000000000000000000000000000000000000000000000000000000",
                    "from": "__WALLET_ADDRESS__",
                    "to": "0xdead0000000000000000000000000000000000000",
                    "value": "500000000000000000",
                    "gas": "21000",
                    "gasPrice": "20000000000",
                    "gasUsed": "21000",
                    "isError": "1",
                    "txreceipt_status": "0",
                    "confirmations": "12343"
                }
            ]
        }
        """

        // Etherscan empty result (no transactions)
        private const val EMPTY_RESULT_RESPONSE = """
        {
            "status": "0",
            "message": "No transactions found",
            "result": []
        }
        """

        // Etherscan error response (API key invalid)
        private const val ERROR_RESPONSE = """
        {
            "status": "0",
            "message": "Invalid API Key",
            "result": "Invalid API Key provided"
        }
        """
    }

    private fun testAccount(index: Long = 0) = AccountInfo(
        id = ACCOUNT_ID, walletId = 1, name = "Test", amount = "0",
        type = AccountType.Eth, symbol = "ETH", address = null, accountIndex = index,
        derivationPath = "m/44'/60'/$index'/0/0"
    )

    private fun createMockClientFactory(
        transactionsResponse: String? = null
    ): () -> HttpClient {
        val txResponse = transactionsResponse
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
                            // ETH RPC calls (eth_getBalance etc.)
                            else ->
                                respond(
                                    content = BALANCE_RESPONSE,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json")
                                )
                        }
                    }
                }
            }
        }
    }

    private fun createProvider(
        account: AccountInfo = testAccount(),
        params: JsonObject = JsonObject(emptyMap()),
        fakeTransactionRepo: FakeTransactionRepository = FakeTransactionRepository(),
        createClient: () -> HttpClient = createMockClientFactory()
    ): EthProvider {
        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))
        return EthProvider(
            masterKey,
            FakeAccountRepository(mapOf(ACCOUNT_ID to account)),
            params,
            NetworkConfig.testnet("test-api-key"),
            createClient,
            fakeTransactionRepo
        )
    }

    private suspend fun resolveResponse(provider: EthProvider, template: String): String {
        val walletAddress = provider.getAddress(ACCOUNT_ID)
        return template.replace("__WALLET_ADDRESS__", walletAddress)
    }

    @Test
    fun sync_fetchesEthTransactionsAndPersists() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, ETH_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        assertEquals(2, transactions.size, "Should parse and persist 2 ETH transactions")
    }

    @Test
    fun directionOutgoing_whenFromMatchesWallet() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, ETH_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val outgoingTx = transactions.find {
            it.txHash == "0xabc1230000000000000000000000000000000000000000000000000000000000"
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
        val response = resolveResponse(provider, ETH_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val incomingTx = transactions.find {
            it.txHash == "0xdef4560000000000000000000000000000000000000000000000000000000000"
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
        val response = resolveResponse(provider, ETH_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val tx = transactions.first()
        // gasUsed=21000, gasPrice=20000000000 -> 420000000000000
        assertEquals("420000000000000", tx.fee,
            "Fee should be gasUsed * gasPrice in wei")
    }

    @Test
    fun timestampConvertedFromSecondsToMilliseconds() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, ETH_TRANSACTIONS_TEMPLATE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val tx = transactions.find {
            it.txHash == "0xabc1230000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(tx)
        // Etherscan timeStamp=1700000000 (seconds) -> stored as 1700000000000 (ms)
        assertEquals(1700000000000L, tx.timestamp,
            "Timestamp should be converted from seconds to milliseconds")
    }

    @Test
    fun incrementalSyncUsesStartblockFromParams() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val fakeAccountRepo = FakeAccountRepository(mapOf(ACCOUNT_ID to testAccount()))
        var capturedUrl = ""

        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))
        val paramsWithBlock = JsonObject(mapOf("lastSyncBlock" to JsonPrimitive(18999)))
        val provider = EthProvider(
            masterKey,
            fakeAccountRepo,
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
                                    content = BALANCE_RESPONSE,
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
    fun lastSyncBlockUpdatedAfterSuccessfulFetch() = runTest {
        var updatedParams: String? = null
        val fakeAccountRepo = object : com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository {
            private val accounts = mutableMapOf(ACCOUNT_ID to testAccount())
            override fun getAccountsByWalletFlow(walletId: Long) = kotlinx.coroutines.flow.flowOf(emptyList<AccountInfo>())
            override fun getTokensByParentFlow(parentId: String) = kotlinx.coroutines.flow.flowOf(emptyList<AccountInfo>())
            override fun getNativeAccountsByWalletFlow(walletId: Long) = kotlinx.coroutines.flow.flowOf(emptyList<AccountInfo>())
            override suspend fun getAccount(id: String) = accounts[id]
            override suspend fun getMaxAccountIndexByWalletAndAccountType(walletId: Long, type: String): Long? = null
            override suspend fun existsByDerivationPath(walletId: Long, derivationPath: String): Boolean = false
            override suspend fun existsTokenForParent(parentId: String, tokenAddress: String): Boolean = false
            override suspend fun countTokensByParent(parentId: String): Int = 0
            override suspend fun getNativeAccountsByWalletAndType(walletId: Long, type: String): List<AccountInfo> = emptyList()
            override suspend fun insertAccount(account: AccountInfo) {}
            override suspend fun updateAmount(accountId: String, amount: String) {}
            override suspend fun updateParams(accountId: String, params: String) { updatedParams = params }
            override suspend fun deleteAccount(id: String) {}
            override suspend fun deleteAccountsByWallet(walletId: Long) {}
        }

        val fakeTransactionRepo = FakeTransactionRepository()
        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))

        // First, get the wallet address
        val tempProvider = EthProvider(
            masterKey, fakeAccountRepo, JsonObject(emptyMap()),
            NetworkConfig.testnet("test-api-key"), createMockClientFactory(), fakeTransactionRepo
        )
        val walletAddress = tempProvider.getAddress(ACCOUNT_ID)
        val response = ETH_TRANSACTIONS_TEMPLATE.replace("__WALLET_ADDRESS__", walletAddress)

        val provider = EthProvider(
            masterKey, fakeAccountRepo, JsonObject(emptyMap()),
            NetworkConfig.testnet("test-api-key"),
            createMockClientFactory(transactionsResponse = response),
            fakeTransactionRepo
        )

        provider.sync(ACCOUNT_ID)

        assertNotNull(updatedParams, "Params should be updated after fetch")
        assertTrue(updatedParams!!.contains("lastSyncBlock"),
            "Updated params should contain lastSyncBlock")
        assertTrue(updatedParams!!.contains("19000001"),
            "lastSyncBlock should be max blockNumber from transactions")
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

    @Test
    fun isErrorOne_mapsToFailedStatus() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory()
        )
        val response = resolveResponse(provider, FAILED_TX_RESPONSE)
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = response)
        )

        providerWithMock.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val failedTx = transactions.find {
            it.txHash == "0xfail000000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(failedTx, "Should find the failed transaction")
        assertEquals(TransactionStatus.FAILED, failedTx.status,
            "isError=1 should map to FAILED status")
    }
}

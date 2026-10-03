package com.ultrabytecoder.coinsafebox.providers

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.TransactionDirection
import com.ultrabytecoder.coinsafebox.domain.model.TransactionStatus
import com.ultrabytecoder.coinsafebox.providers.ton.TonProvider
import fr.acinq.secp256k1.Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TonProviderSyncTest {

    companion object {
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val ACCOUNT_ID = "test-account-id"

        // TonCenter balance response
        private const val BALANCE_RESPONSE = """{"ok":true,"result":"1000000000"}"""

        // Two TON transactions: one outgoing, one incoming
        private const val TON_TRANSACTIONS_RESPONSE_TEMPLATE = """
        {
            "ok": true,
            "result": [
                {
                    "transaction_id": {"lt": "123456789", "hash": "abchash001"},
                    "utime": 1700000000,
                    "in_msg": {
                        "source": "__OTHER_ADDRESS__",
                        "destination": "__WALLET_ADDRESS__",
                        "value": "2000000000"
                    },
                    "out_msgs": [],
                    "fee": 500000,
                    "in_count": 1,
                    "out_count": 0
                },
                {
                    "transaction_id": {"lt": "123456790", "hash": "abchash002"},
                    "utime": 1700000001,
                    "in_msg": {
                        "source": "__WALLET_ADDRESS__",
                        "destination": "__OTHER_ADDRESS__",
                        "value": "1000000000"
                    },
                    "out_msgs": [
                        {
                            "source": "__WALLET_ADDRESS__",
                            "destination": "__OTHER_ADDRESS__",
                            "value": "800000000"
                        }
                    ],
                    "fee": 1000000,
                    "in_count": 0,
                    "out_count": 1
                }
            ]
        }
        """

        private const val EMPTY_TRANSACTIONS_RESPONSE = """
        {"ok":true,"result":[]}
        """

        // Multi-message transaction (2 out_msgs but should produce 1 TransactionInfo)
        private const val MULTI_MSG_RESPONSE_TEMPLATE = """
        {
            "ok": true,
            "result": [
                {
                    "transaction_id": {"lt": "123456791", "hash": "abchash003"},
                    "utime": 1700000002,
                    "in_msg": {
                        "source": "__WALLET_ADDRESS__",
                        "destination": "__WALLET_ADDRESS__",
                        "value": "3000000000"
                    },
                    "out_msgs": [
                        {
                            "source": "__WALLET_ADDRESS__",
                            "destination": "__OTHER_ADDRESS__",
                            "value": "1000000000"
                        },
                        {
                            "source": "__WALLET_ADDRESS__",
                            "destination": "__ANOTHER_ADDRESS__",
                            "value": "1500000000"
                        }
                    ],
                    "fee": 2000000,
                    "in_count": 1,
                    "out_count": 2
                }
            ]
        }
        """
    }

    private fun testAccount(index: Long = 0) = AccountInfo(
        id = ACCOUNT_ID, walletId = 1, name = "Test", amount = "0",
        type = AccountType.Ton(), symbol = "GRAM", address = null, accountIndex = index,
        derivationPath = "m/44'/607'/$index'"
    )

    private fun createMockClientFactory(
        balanceResponse: String = BALANCE_RESPONSE,
        transactionsResponse: String? = null
    ): () -> HttpClient {
        val txResponse = transactionsResponse
        return {
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when {
                            request.url.encodedPath.contains("/getAddressBalance") ->
                                respond(
                                    content = balanceResponse,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json")
                                )
                            request.url.encodedPath.contains("/getTransactions") ->
                                respond(
                                    content = txResponse ?: EMPTY_TRANSACTIONS_RESPONSE,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json")
                                )
                            else ->
                                respond(
                                    content = "{}",
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
        fakeTransactionRepo: FakeTransactionRepository = FakeTransactionRepository(),
        createClient: () -> HttpClient = createMockClientFactory()
    ): TonProvider {
        return TonProvider(
            Hex.decode(SEED_HEX),
            FakeAccountRepository(mapOf(ACCOUNT_ID to account)),
            JsonObject(emptyMap()),
            NetworkConfig.testnet("test-api-key"),
            fakeTransactionRepo,
            createClient
        )
    }

    @Test
    fun sync_fetchesAndPersistsTonTransactions() = runTest {
        val provider = createProvider()
        val walletAddress = provider.getAddress(ACCOUNT_ID)
        val otherAddress = "EQDotheraddress000000000000000000000000000000000000000"

        val responseJson = TON_TRANSACTIONS_RESPONSE_TEMPLATE
            .replace("__WALLET_ADDRESS__", walletAddress)
            .replace("__OTHER_ADDRESS__", otherAddress)

        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = responseJson)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        assertEquals(2, transactions.size, "Should parse and persist 2 transactions")
    }

    @Test
    fun directionOutgoing_whenInMsgSourceMatchesWallet() = runTest {
        val provider = createProvider()
        val walletAddress = provider.getAddress(ACCOUNT_ID)
        val otherAddress = "EQDotheraddress000000000000000000000000000000000000000"

        val responseJson = TON_TRANSACTIONS_RESPONSE_TEMPLATE
            .replace("__WALLET_ADDRESS__", walletAddress)
            .replace("__OTHER_ADDRESS__", otherAddress)

        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = responseJson)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val outgoingTx = transactions.find { it.txHash == "123456790:abchash002" }
        assertEquals(
            TransactionDirection.OUTGOING,
            outgoingTx?.direction,
            "Second tx should be OUTGOING (in_msg.source is wallet)"
        )
    }

    @Test
    fun directionIncoming_whenInMsgDestinationMatchesWallet() = runTest {
        val provider = createProvider()
        val walletAddress = provider.getAddress(ACCOUNT_ID)
        val otherAddress = "EQDotheraddress000000000000000000000000000000000000000"

        val responseJson = TON_TRANSACTIONS_RESPONSE_TEMPLATE
            .replace("__WALLET_ADDRESS__", walletAddress)
            .replace("__OTHER_ADDRESS__", otherAddress)

        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = responseJson)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val incomingTx = transactions.find { it.txHash == "123456789:abchash001" }
        assertEquals(
            TransactionDirection.INCOMING,
            incomingTx?.direction,
            "First tx should be INCOMING (in_msg.destination is wallet)"
        )
    }

    @Test
    fun timestampConvertedFromSecondsToMilliseconds() = runTest {
        val provider = createProvider()
        val walletAddress = provider.getAddress(ACCOUNT_ID)
        val otherAddress = "EQDotheraddress000000000000000000000000000000000000000"

        val responseJson = TON_TRANSACTIONS_RESPONSE_TEMPLATE
            .replace("__WALLET_ADDRESS__", walletAddress)
            .replace("__OTHER_ADDRESS__", otherAddress)

        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = responseJson)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val tx = transactions.find { it.txHash == "123456789:abchash001" }
        assertEquals(
            1700000000000L,
            tx?.timestamp,
            "utime 1700000000 (seconds) should become 1700000000000 (milliseconds)"
        )
    }

    @Test
    fun multiMessageTransactionProducesSingleTransactionInfo() = runTest {
        val provider = createProvider()
        val walletAddress = provider.getAddress(ACCOUNT_ID)
        val otherAddress = "EQDotheraddress000000000000000000000000000000000000000"
        val anotherAddress = "EQDanotheraddr000000000000000000000000000000000000000"

        val responseJson = MULTI_MSG_RESPONSE_TEMPLATE
            .replace("__WALLET_ADDRESS__", walletAddress)
            .replace("__OTHER_ADDRESS__", otherAddress)
            .replace("__ANOTHER_ADDRESS__", anotherAddress)

        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = responseJson)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        assertEquals(1, transactions.size, "Multi-message tx should produce 1 TransactionInfo, not 2")
    }

    @Test
    fun statusAlwaysConfirmedForTon() = runTest {
        val provider = createProvider()
        val walletAddress = provider.getAddress(ACCOUNT_ID)
        val otherAddress = "EQDotheraddress000000000000000000000000000000000000000"

        val responseJson = TON_TRANSACTIONS_RESPONSE_TEMPLATE
            .replace("__WALLET_ADDRESS__", walletAddress)
            .replace("__OTHER_ADDRESS__", otherAddress)

        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = responseJson)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        transactions.forEach { tx ->
            assertEquals(
                TransactionStatus.CONFIRMED,
                tx.status,
                "TON tx ${tx.txHash} should have CONFIRMED status"
            )
        }
    }

    @Test
    fun emptyResultReturnsEmptyList_noCrash() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = EMPTY_TRANSACTIONS_RESPONSE)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        assertEquals(0, transactions.size, "Empty result array should result in no transactions")
    }

    @Test
    fun feeStoredAsNanotonsString() = runTest {
        val provider = createProvider()
        val walletAddress = provider.getAddress(ACCOUNT_ID)
        val otherAddress = "EQDotheraddress000000000000000000000000000000000000000"

        val responseJson = TON_TRANSACTIONS_RESPONSE_TEMPLATE
            .replace("__WALLET_ADDRESS__", walletAddress)
            .replace("__OTHER_ADDRESS__", otherAddress)

        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = responseJson)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        // Second tx has fee: 1000000
        val feeTx = transactions.find { it.txHash == "123456790:abchash002" }
        assertEquals("1000000", feeTx?.fee, "Fee should be stored as nanotons string")
    }
}

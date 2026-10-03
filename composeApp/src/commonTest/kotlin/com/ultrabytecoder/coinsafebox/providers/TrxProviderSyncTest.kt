package com.ultrabytecoder.coinsafebox.providers

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.TransactionDirection
import com.ultrabytecoder.coinsafebox.domain.model.TransactionStatus
import com.ultrabytecoder.coinsafebox.providers.tron.TrxProvider
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
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrxProviderSyncTest {

    companion object {
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val ACCOUNT_ID = "test-account-id"

        // TronGrid balance response (wallet/getaccount)
        private const val BALANCE_RESPONSE = """{"balance":1000000}"""

        // Two TRX transactions: one outgoing, one incoming
        // Owner: known hex address, To: different hex address
        private const val TRX_TRANSACTIONS_RESPONSE = """
        {
            "data": [
                {
                    "ret": [{"contractRet": "SUCCESS"}],
                    "txID": "abc1230000000000000000000000000000000000000000000000000000000000",
                    "raw_data": {
                        "contract": [{
                            "type": "TransferContract",
                            "parameter": {
                                "value": {
                                    "amount": 5000000,
                                    "owner_address": "__OWNER_HEX__",
                                    "to_address": "__TO_HEX__"
                                }
                            }
                        }],
                        "timestamp": 1700000000000,
                        "block_number": 50000000
                    },
                    "block_timestamp": 1700000000000
                },
                {
                    "ret": [{"contractRet": "REVERT"}],
                    "txID": "def4560000000000000000000000000000000000000000000000000000000000",
                    "raw_data": {
                        "contract": [{
                            "type": "TransferContract",
                            "parameter": {
                                "value": {
                                    "amount": 3000000,
                                    "owner_address": "__TO_HEX__",
                                    "to_address": "__OWNER_HEX__"
                                }
                            }
                        }],
                        "timestamp": 1700000001000,
                        "block_number": 50000001
                    },
                    "block_timestamp": 1700000001000
                }
            ],
            "meta": {"at": 1700000001000, "fingerprint": "fp123"}
        }
        """

        // Response with fee data in ret[0].fee (1 TRX = 1000000 SUN)
        private const val TRX_TRANSACTIONS_WITH_FEE_RESPONSE = """
        {
            "data": [
                {
                    "ret": [{"contractRet": "SUCCESS", "fee": 1000000}],
                    "txID": "fee1110000000000000000000000000000000000000000000000000000000000",
                    "raw_data": {
                        "contract": [{
                            "type": "TransferContract",
                            "parameter": {
                                "value": {
                                    "amount": 5000000,
                                    "owner_address": "__OWNER_HEX__",
                                    "to_address": "__TO_HEX__"
                                }
                            }
                        }],
                        "timestamp": 1700000002000,
                        "block_number": 50000002
                    },
                    "block_timestamp": 1700000002000
                }
            ],
            "meta": {"at": 1700000002000, "fingerprint": "fpFee"}
        }
        """

        // Response where ret has no fee field (fee missing, not zero)
        private const val TRX_TRANSACTIONS_NO_FEE_RESPONSE = """
        {
            "data": [
                {
                    "ret": [{"contractRet": "SUCCESS"}],
                    "txID": "nofee00000000000000000000000000000000000000000000000000000000000",
                    "raw_data": {
                        "contract": [{
                            "type": "TransferContract",
                            "parameter": {
                                "value": {
                                    "amount": 2000000,
                                    "owner_address": "__OWNER_HEX__",
                                    "to_address": "__TO_HEX__"
                                }
                            }
                        }],
                        "timestamp": 1700000003000,
                        "block_number": 50000003
                    },
                    "block_timestamp": 1700000003000
                }
            ],
            "meta": {"at": 1700000003000, "fingerprint": "fpNoFee"}
        }
        """

        private const val EMPTY_TRANSACTIONS_RESPONSE = """
        {"data":[],"meta":{"at":0,"fingerprint":""}}
        """
    }

    private fun testAccount(index: Long = 0) = AccountInfo(
        id = ACCOUNT_ID, walletId = 1, name = "Test", amount = "0",
        type = AccountType.Trx, symbol = "TRX", address = null, accountIndex = index,
        derivationPath = "m/44'/195'/$index'/0/0"
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
                            request.url.encodedPath.contains("/wallet/getaccount") ->
                                respond(
                                    content = balanceResponse,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                            request.url.encodedPath.contains("/transactions") ->
                                respond(
                                    content = txResponse ?: EMPTY_TRANSACTIONS_RESPONSE,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
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
    ): TrxProvider {
        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))
        return TrxProvider(
            masterKey,
            FakeAccountRepository(mapOf(ACCOUNT_ID to account)),
            JsonObject(emptyMap()),
            NetworkConfig.testnet("test-api-key"),
            fakeTransactionRepo,
            createClient
        )
    }

    @Test
    fun base58ToHexAddress_convertsTprefixTo41prefix() = runTest {
        // Derive a known TRX address from the seed and convert it
        val provider = createProvider()
        val base58Address = provider.getAddress(ACCOUNT_ID)
        val hexAddress = base58ToHexAddress(base58Address)

        assertTrue(hexAddress.startsWith("41"), "Hex address should start with 41 prefix")
        assertEquals(42, hexAddress.length, "Hex address should be 42 characters (21 bytes hex encoded)")
    }

    @Test
    fun hexToBase58Address_converts41prefixToTprefix() = runTest {
        val provider = createProvider()
        val base58Address = provider.getAddress(ACCOUNT_ID)
        val hexAddress = base58ToHexAddress(base58Address)
        val roundTrip = hexToBase58Address(hexAddress)

        assertEquals(base58Address, roundTrip, "Round-trip base58->hex->base58 should match original")
    }

    @Test
    fun hexToBase58Address_and_base58ToHexAddress_roundtrip() = runTest {
        val provider = createProvider()
        val originalBase58 = provider.getAddress(ACCOUNT_ID)

        val hex = base58ToHexAddress(originalBase58)
        val backToBase58 = hexToBase58Address(hex)

        assertEquals(originalBase58, backToBase58)
    }

    @Test
    fun fetchTransactions_parsesResponseAndPersistsTransactions() = runTest {
        val provider = createProvider()
        val base58Address = provider.getAddress(ACCOUNT_ID)
        val ownerHex = base58ToHexAddress(base58Address)
        val toHex = "41bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        val responseJson = TRX_TRANSACTIONS_RESPONSE
            .replace("__OWNER_HEX__", ownerHex)
            .replace("__TO_HEX__", toHex)

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
    fun fetchTransactions_directionOutgoing_whenOwnerMatches() = runTest {
        val provider = createProvider()
        val base58Address = provider.getAddress(ACCOUNT_ID)
        val ownerHex = base58ToHexAddress(base58Address)
        val toHex = "41bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        val responseJson = TRX_TRANSACTIONS_RESPONSE
            .replace("__OWNER_HEX__", ownerHex)
            .replace("__TO_HEX__", toHex)

        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = responseJson)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val outgoingTx = transactions.find {
            it.txHash == "abc1230000000000000000000000000000000000000000000000000000000000"
        }
        assertEquals(TransactionDirection.OUTGOING, outgoingTx?.direction, "First tx should be OUTGOING (owner is wallet)")
    }

    @Test
    fun fetchTransactions_directionIncoming_whenToMatches() = runTest {
        val provider = createProvider()
        val base58Address = provider.getAddress(ACCOUNT_ID)
        val ownerHex = base58ToHexAddress(base58Address)
        val toHex = "41bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        val responseJson = TRX_TRANSACTIONS_RESPONSE
            .replace("__OWNER_HEX__", ownerHex)
            .replace("__TO_HEX__", toHex)

        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = responseJson)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val incomingTx = transactions.find {
            it.txHash == "def4560000000000000000000000000000000000000000000000000000000000"
        }
        assertEquals(TransactionDirection.INCOMING, incomingTx?.direction, "Second tx should be INCOMING (to is wallet)")
    }

    @Test
    fun fetchTransactions_statusConfirmed_whenContractRetSuccess() = runTest {
        val provider = createProvider()
        val base58Address = provider.getAddress(ACCOUNT_ID)
        val ownerHex = base58ToHexAddress(base58Address)
        val toHex = "41bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        val responseJson = TRX_TRANSACTIONS_RESPONSE
            .replace("__OWNER_HEX__", ownerHex)
            .replace("__TO_HEX__", toHex)

        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = responseJson)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val confirmedTx = transactions.find {
            it.txHash == "abc1230000000000000000000000000000000000000000000000000000000000"
        }
        assertEquals(TransactionStatus.CONFIRMED, confirmedTx?.status, "contractRet SUCCESS should map to CONFIRMED")
    }

    @Test
    fun fetchTransactions_statusFailed_whenContractRetRevert() = runTest {
        val provider = createProvider()
        val base58Address = provider.getAddress(ACCOUNT_ID)
        val ownerHex = base58ToHexAddress(base58Address)
        val toHex = "41bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        val responseJson = TRX_TRANSACTIONS_RESPONSE
            .replace("__OWNER_HEX__", ownerHex)
            .replace("__TO_HEX__", toHex)

        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = responseJson)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val failedTx = transactions.find {
            it.txHash == "def4560000000000000000000000000000000000000000000000000000000000"
        }
        assertEquals(TransactionStatus.FAILED, failedTx?.status, "contractRet REVERT should map to FAILED")
    }

    @Test
    fun fetchTransactions_emptyDataArray_returnsEmptyList() = runTest {
        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = EMPTY_TRANSACTIONS_RESPONSE)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        assertEquals(0, transactions.size, "Empty data array should result in no transactions")
    }

    @Test
    fun fetchTransactions_counterpartyAddress_isBase58Format() = runTest {
        val provider = createProvider()
        val base58Address = provider.getAddress(ACCOUNT_ID)
        val ownerHex = base58ToHexAddress(base58Address)
        val toHex = "41bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val expectedCounterpartyBase58 = hexToBase58Address(toHex)

        val responseJson = TRX_TRANSACTIONS_RESPONSE
            .replace("__OWNER_HEX__", ownerHex)
            .replace("__TO_HEX__", toHex)

        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = responseJson)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val outgoingTx = transactions.find {
            it.txHash == "abc1230000000000000000000000000000000000000000000000000000000000"
        }
        assertEquals(expectedCounterpartyBase58, outgoingTx?.counterpartyAddress,
            "Counterparty address should be converted to base58 format")
    }

    @Test
    fun fetchTransactions_feeExtracted_fromRetFeeField() = runTest {
        val provider = createProvider()
        val base58Address = provider.getAddress(ACCOUNT_ID)
        val ownerHex = base58ToHexAddress(base58Address)
        val toHex = "41bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        val responseJson = TRX_TRANSACTIONS_WITH_FEE_RESPONSE
            .replace("__OWNER_HEX__", ownerHex)
            .replace("__TO_HEX__", toHex)

        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = responseJson)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val feeTx = transactions.find {
            it.txHash == "fee1110000000000000000000000000000000000000000000000000000000000"
        }
        assertEquals("1000000", feeTx?.fee,
            "Fee should be extracted from ret[0].fee as SUN string")
    }

    @Test
    fun fetchTransactions_feeIsNull_whenRetFeeMissing() = runTest {
        val provider = createProvider()
        val base58Address = provider.getAddress(ACCOUNT_ID)
        val ownerHex = base58ToHexAddress(base58Address)
        val toHex = "41bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        val responseJson = TRX_TRANSACTIONS_NO_FEE_RESPONSE
            .replace("__OWNER_HEX__", ownerHex)
            .replace("__TO_HEX__", toHex)

        val fakeTransactionRepo = FakeTransactionRepository()
        val providerWithMock = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(transactionsResponse = responseJson)
        )

        providerWithMock.syncTransactions(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val noFeeTx = transactions.find {
            it.txHash == "nofee00000000000000000000000000000000000000000000000000000000000"
        }
        assertEquals(null, noFeeTx?.fee,
            "Fee should be null when ret[0] does not contain fee field")
    }
}

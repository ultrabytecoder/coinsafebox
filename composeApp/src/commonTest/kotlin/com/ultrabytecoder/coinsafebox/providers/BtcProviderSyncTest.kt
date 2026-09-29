package com.ultrabytecoder.coinsafebox.providers

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.TransactionDirection
import com.ultrabytecoder.coinsafebox.domain.model.TransactionStatus
import fr.acinq.bitcoin.Bitcoin
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BtcProviderSyncTest {

    companion object {
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val ACCOUNT_ID = "test-account-id"

        private const val UTXO_EMPTY_RESPONSE = "[]"

        // Confirmed transaction: OUTGOING from own receive address, one output to external, one change to own change address
        private const val OUTGOING_TX_TEMPLATE = """
        [
            {
                "txid": "aaa1110000000000000000000000000000000000000000000000000000000000",
                "fee": 456,
                "vin": [{
                    "prevout": {
                        "scriptpubkey_address": "__RECEIVE_ADDR__",
                        "value": 50000
                    }
                }],
                "vout": [
                    {
                        "scriptpubkey_address": "bc1qexternal00000000000000000000000000000",
                        "value": 45000
                    },
                    {
                        "scriptpubkey_address": "__CHANGE_ADDR__",
                        "value": 4544
                    }
                ],
                "status": {
                    "confirmed": true,
                    "block_height": 800000,
                    "block_time": 1700000000
                }
            }
        ]
        """

        // Incoming transaction: no inputs from own addresses, output to own receive address
        private const val INCOMING_TX_TEMPLATE = """
        [
            {
                "txid": "bbb2220000000000000000000000000000000000000000000000000000000000",
                "fee": 250,
                "vin": [{
                    "prevout": {
                        "scriptpubkey_address": "bc1qsender0000000000000000000000000000000",
                        "value": 30000
                    }
                }],
                "vout": [
                    {
                        "scriptpubkey_address": "__RECEIVE_ADDR__",
                        "value": 29000
                    },
                    {
                        "scriptpubkey_address": "bc1qsenderchange0000000000000000000000000",
                        "value": 750
                    }
                ],
                "status": {
                    "confirmed": true,
                    "block_height": 800001,
                    "block_time": 1700001000
                }
            }
        ]
        """

        // Transaction touching multiple own addresses (receive + change) - same txid appears on both addresses
        private const val MULTI_ADDR_TX_FOR_RECEIVE = """
        [
            {
                "txid": "ccc3330000000000000000000000000000000000000000000000000000000000",
                "fee": 789,
                "vin": [{
                    "prevout": {
                        "scriptpubkey_address": "__RECEIVE_ADDR__",
                        "value": 100000
                    }
                }],
                "vout": [
                    {
                        "scriptpubkey_address": "bc1qdest000000000000000000000000000000000",
                        "value": 90000
                    },
                    {
                        "scriptpubkey_address": "__CHANGE_ADDR__",
                        "value": 9211
                    }
                ],
                "status": {
                    "confirmed": true,
                    "block_height": 800002,
                    "block_time": 1700002000
                }
            }
        ]
        """

        private const val MULTI_ADDR_TX_FOR_CHANGE = """
        [
            {
                "txid": "ccc3330000000000000000000000000000000000000000000000000000000000",
                "fee": 789,
                "vin": [{
                    "prevout": {
                        "scriptpubkey_address": "__RECEIVE_ADDR__",
                        "value": 100000
                    }
                }],
                "vout": [
                    {
                        "scriptpubkey_address": "bc1qdest000000000000000000000000000000000",
                        "value": 90000
                    },
                    {
                        "scriptpubkey_address": "__CHANGE_ADDR__",
                        "value": 9211
                    }
                ],
                "status": {
                    "confirmed": true,
                    "block_height": 800002,
                    "block_time": 1700002000
                }
            }
        ]
        """

        // Unconfirmed transaction: status.confirmed = false, no block_height or block_time
        private const val UNCONFIRMED_TX_TEMPLATE = """
        [
            {
                "txid": "ddd4440000000000000000000000000000000000000000000000000000000000",
                "fee": 300,
                "vin": [{
                    "prevout": {
                        "scriptpubkey_address": "__RECEIVE_ADDR__",
                        "value": 60000
                    }
                }],
                "vout": [
                    {
                        "scriptpubkey_address": "bc1qpendingdest0000000000000000000000000",
                        "value": 59000
                    }
                ],
                "status": {
                    "confirmed": false
                }
            }
        ]
        """

        private const val EMPTY_TX_RESPONSE = "[]"
    }

    private fun testAccount(index: Long = 0) = AccountInfo(
        id = ACCOUNT_ID, walletId = 1, name = "Test", amount = "0",
        type = AccountType.Btc, symbol = "BTC", address = null, accountIndex = index,
        derivationPath = "m/84'/1'/$index'"
    )

    private fun deriveAddresses(): Pair<String, String> {
        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))
        val networkConfig = NetworkConfig.testnet("test-api-key")
        val receiveKey = masterKey.derivePrivateKey(
            listOf(
                DeterministicWallet.hardened(84),
                DeterministicWallet.hardened(networkConfig.btcBip84CoinType),
                DeterministicWallet.hardened(0L),
                0L,
                0L
            )
        )
        val changeKey = masterKey.derivePrivateKey(
            listOf(
                DeterministicWallet.hardened(84),
                DeterministicWallet.hardened(networkConfig.btcBip84CoinType),
                DeterministicWallet.hardened(0L),
                1L,
                0L
            )
        )
        val receiveAddr = Bitcoin.computeBIP84Address(receiveKey.publicKey, networkConfig.btcGenesisBlockHash)
        val changeAddr = Bitcoin.computeBIP84Address(changeKey.publicKey, networkConfig.btcGenesisBlockHash)
        return receiveAddr to changeAddr
    }

    private fun createMockClientFactory(
        utxoResponse: String = UTXO_EMPTY_RESPONSE,
        txResponses: Map<String, String> = emptyMap()
    ): () -> HttpClient {
        return {
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val path = request.url.encodedPath
                        when {
                            path.contains("/utxo") ->
                                respond(
                                    content = utxoResponse,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json")
                                )
                            path.contains("/txs") -> {
                                // Find matching response by checking address in the path
                                val matchedResponse = txResponses.entries.firstOrNull { (addr, _) ->
                                    path.contains(addr)
                                }?.value ?: EMPTY_TX_RESPONSE
                                respond(
                                    content = matchedResponse,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json")
                                )
                            }
                            path.contains("/fees/recommended") ->
                                respond(
                                    content = """{"fastestFee":10,"halfHourFee":8,"hourFee":5,"economyFee":3,"minimumFee":1}""",
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
    ): BtcProvider {
        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))
        return BtcProvider(
            masterKey,
            null,
            FakeUtxoRepository(emptyList()),
            FakeAccountRepository(mapOf(ACCOUNT_ID to account)),
            JsonObject(emptyMap()),
            NetworkConfig.testnet("test-api-key"),
            fakeTransactionRepo,
            createClient
        )
    }

    @Test
    fun sync_fetchesAndPersistsTransactions() = runTest {
        val (receiveAddr, _) = deriveAddresses()
        val fakeTransactionRepo = FakeTransactionRepository()

        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(
                txResponses = mapOf(receiveAddr to OUTGOING_TX_TEMPLATE.replace("__RECEIVE_ADDR__", receiveAddr))
            )
        )

        provider.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        assertTrue(transactions.isNotEmpty(), "sync() should fetch and persist transactions")
    }

    @Test
    fun multiAddressAggregation_singleTransactionForTwoAddresses() = runTest {
        val (receiveAddr, changeAddr) = deriveAddresses()
        val fakeTransactionRepo = FakeTransactionRepository()

        val receiveTx = MULTI_ADDR_TX_FOR_RECEIVE
            .replace("__RECEIVE_ADDR__", receiveAddr)
            .replace("__CHANGE_ADDR__", changeAddr)
        val changeTx = MULTI_ADDR_TX_FOR_CHANGE
            .replace("__RECEIVE_ADDR__", receiveAddr)
            .replace("__CHANGE_ADDR__", changeAddr)

        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(
                txResponses = mapOf(
                    receiveAddr to receiveTx,
                    changeAddr to changeTx
                )
            )
        )

        provider.sync(ACCOUNT_ID, SyncMode.FULL)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val aggregatedTx = transactions.filter {
            it.txHash == "ccc3330000000000000000000000000000000000000000000000000000000000"
        }
        assertEquals(1, aggregatedTx.size, "Transaction touching multiple own addresses should be aggregated into single TransactionInfo")
    }

    @Test
    fun directionOutgoing_whenVinPrevoutMatchesOwnAddress() = runTest {
        val (receiveAddr, _) = deriveAddresses()
        val fakeTransactionRepo = FakeTransactionRepository()

        val txJson = OUTGOING_TX_TEMPLATE.replace("__RECEIVE_ADDR__", receiveAddr)
            .replace("__CHANGE_ADDR__", "bc1qsomechangeaddr00000000000000000000")

        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(
                txResponses = mapOf(receiveAddr to txJson)
            )
        )

        provider.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val outgoingTx = transactions.find {
            it.txHash == "aaa1110000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(outgoingTx, "Outgoing transaction should exist")
        assertEquals(TransactionDirection.OUTGOING, outgoingTx.direction, "Transaction with own input should be OUTGOING")
    }

    @Test
    fun directionIncoming_whenOnlyVoutMatchesOwnAddress() = runTest {
        val (receiveAddr, _) = deriveAddresses()
        val fakeTransactionRepo = FakeTransactionRepository()

        val txJson = INCOMING_TX_TEMPLATE.replace("__RECEIVE_ADDR__", receiveAddr)

        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(
                txResponses = mapOf(receiveAddr to txJson)
            )
        )

        provider.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val incomingTx = transactions.find {
            it.txHash == "bbb2220000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(incomingTx, "Incoming transaction should exist")
        assertEquals(TransactionDirection.INCOMING, incomingTx.direction, "Transaction with only vout to own address should be INCOMING")
    }

    @Test
    fun outgoingAmount_excludesChangeOutputs() = runTest {
        val (receiveAddr, changeAddr) = deriveAddresses()
        val fakeTransactionRepo = FakeTransactionRepository()

        val txJson = OUTGOING_TX_TEMPLATE.replace("__RECEIVE_ADDR__", receiveAddr)
            .replace("__CHANGE_ADDR__", changeAddr)

        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(
                txResponses = mapOf(
                    receiveAddr to txJson,
                    changeAddr to txJson
                )
            )
        )

        provider.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val outgoingTx = transactions.find {
            it.txHash == "aaa1110000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(outgoingTx)
        // Amount should be 45000 (only the non-own vout), NOT 45000 + 4544
        assertEquals("45000", outgoingTx.amount, "Outgoing amount should exclude change outputs to own addresses")
    }

    @Test
    fun feeExtracted_fromMempoolSpaceFeeField() = runTest {
        val (receiveAddr, _) = deriveAddresses()
        val fakeTransactionRepo = FakeTransactionRepository()

        val txJson = OUTGOING_TX_TEMPLATE.replace("__RECEIVE_ADDR__", receiveAddr)
            .replace("__CHANGE_ADDR__", "bc1qsomechangeaddr00000000000000000000")

        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(
                txResponses = mapOf(receiveAddr to txJson)
            )
        )

        provider.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val tx = transactions.find {
            it.txHash == "aaa1110000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(tx)
        assertEquals("456", tx.fee, "Fee should be extracted from mempool.space fee field in satoshis")
    }

    @Test
    fun unconfirmedTx_hasPendingStatusAndNullBlockHeight() = runTest {
        val (receiveAddr, _) = deriveAddresses()
        val fakeTransactionRepo = FakeTransactionRepository()

        val txJson = UNCONFIRMED_TX_TEMPLATE.replace("__RECEIVE_ADDR__", receiveAddr)

        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(
                txResponses = mapOf(receiveAddr to txJson)
            )
        )

        provider.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val unconfirmedTx = transactions.find {
            it.txHash == "ddd4440000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(unconfirmedTx, "Unconfirmed transaction should be persisted")
        assertEquals(TransactionStatus.PENDING, unconfirmedTx.status, "Unconfirmed tx should have PENDING status")
        assertNull(unconfirmedTx.blockHeight, "Unconfirmed tx should have null blockHeight")
    }

    @Test
    fun confirmedTx_hasConfirmedStatusWithBlockHeight() = runTest {
        val (receiveAddr, _) = deriveAddresses()
        val fakeTransactionRepo = FakeTransactionRepository()

        val txJson = OUTGOING_TX_TEMPLATE.replace("__RECEIVE_ADDR__", receiveAddr)
            .replace("__CHANGE_ADDR__", "bc1qsomechangeaddr00000000000000000000")

        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(
                txResponses = mapOf(receiveAddr to txJson)
            )
        )

        provider.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val confirmedTx = transactions.find {
            it.txHash == "aaa1110000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(confirmedTx)
        assertEquals(TransactionStatus.CONFIRMED, confirmedTx.status, "Confirmed tx should have CONFIRMED status")
        assertEquals(800000L, confirmedTx.blockHeight, "blockHeight should match mempool.space status.block_height")
    }

    @Test
    fun counterpartyAddress_outgoingIsFirstNonOwnVout_incomingIsFirstNonOwnVin() = runTest {
        val (receiveAddr, _) = deriveAddresses()
        val fakeTransactionRepo = FakeTransactionRepository()

        // Test outgoing counterparty
        val outgoingJson = OUTGOING_TX_TEMPLATE.replace("__RECEIVE_ADDR__", receiveAddr)
            .replace("__CHANGE_ADDR__", "bc1qsomechangeaddr00000000000000000000")

        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(
                txResponses = mapOf(receiveAddr to outgoingJson)
            )
        )

        provider.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val outgoingTx = transactions.find {
            it.txHash == "aaa1110000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(outgoingTx)
        assertEquals(
            "bc1qexternal00000000000000000000000000000",
            outgoingTx.counterpartyAddress,
            "Outgoing tx counterparty should be first non-own vout address"
        )

        // Test incoming counterparty
        val fakeTransactionRepo2 = FakeTransactionRepository()
        val incomingJson = INCOMING_TX_TEMPLATE.replace("__RECEIVE_ADDR__", receiveAddr)

        val provider2 = createProvider(
            fakeTransactionRepo = fakeTransactionRepo2,
            createClient = createMockClientFactory(
                txResponses = mapOf(receiveAddr to incomingJson)
            )
        )

        provider2.sync(ACCOUNT_ID)

        val incomingTxs = fakeTransactionRepo2.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        val incomingTx = incomingTxs.find {
            it.txHash == "bbb2220000000000000000000000000000000000000000000000000000000000"
        }
        assertNotNull(incomingTx)
        assertEquals(
            "bc1qsender0000000000000000000000000000000",
            incomingTx.counterpartyAddress,
            "Incoming tx counterparty should be first non-own vin prevout address"
        )
    }

    @Test
    fun emptyMempoolResponse_returnsNoTransactions() = runTest {
        val (receiveAddr, _) = deriveAddresses()
        val fakeTransactionRepo = FakeTransactionRepository()

        val provider = createProvider(
            fakeTransactionRepo = fakeTransactionRepo,
            createClient = createMockClientFactory(
                txResponses = mapOf(receiveAddr to EMPTY_TX_RESPONSE)
            )
        )

        provider.sync(ACCOUNT_ID)

        val transactions = fakeTransactionRepo.getTransactionsByAccount(ACCOUNT_ID, 100, 0)
        assertEquals(0, transactions.size, "Empty mempool response should result in no transactions (no crash)")
    }
}

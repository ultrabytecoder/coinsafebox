package com.ultrabytecoder.coinsafebox.providers

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.UtxoInfo
import fr.acinq.bitcoin.DeterministicWallet
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BtcProviderCreateTransactionTest {

    companion object {
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val ACCOUNT_ID = "test-account-id"
        private const val DEST_ACCOUNT_ID = "dest-account"

        private const val FEE_RATE_RESPONSE = """{"fastestFee":10,"halfHourFee":8,"hourFee":5,"economyFee":3,"minimumFee":1}"""

        private fun createMockClientFactory(feeRateResponse: String = FEE_RATE_RESPONSE): () -> HttpClient = {
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val path = request.url.encodedPath
                        when {
                            path.contains("/fees/recommended") ->
                                respond(
                                    content = feeRateResponse,
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

    private fun testAccount(index: Long = 0) = AccountInfo(
        id = ACCOUNT_ID, walletId = 1, name = "Test", amount = "0",
        type = AccountType.Btc, symbol = "BTC", address = null, accountIndex = index,
        derivationPath = "m/84'/1'/$index'"
    )

    private fun createProvider(
        utxos: List<UtxoInfo> = emptyList(),
        account: AccountInfo = testAccount(),
        destAccount: AccountInfo? = null,
        createClient: () -> HttpClient = createMockClientFactory()
    ): BtcProvider {
        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))
        val accounts = mutableMapOf(ACCOUNT_ID to account)
        destAccount?.let { accounts[DEST_ACCOUNT_ID] = it }
        return BtcProvider(masterKey, FakeUtxoRepository(utxos), FakeAccountRepository(accounts), JsonObject(emptyMap()), NetworkConfig.testnet("test-api-key"), FakeTransactionRepository(), createClient)
    }

    private fun testUtxo(
        id: Long = 1,
        amount: Long = 200_000,
        derivationPath: String = "m/84'/1'/0'/0/0",
        txid: String = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
    ) = UtxoInfo(
        id = id,
        accountId = ACCOUNT_ID,
        derivationPath = derivationPath,
        amount = amount,
        txid = txid,
        vout = 0
    )

    @Test
    fun createTransaction_returnsValidHexEncodedTransaction() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Btc, "BTC", null, 1, "m/84'/1'/1'")
        val provider = createProvider(listOf(testUtxo()), destAccount = dest)
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        val result = provider.createTransaction(destAddress, amount, ACCOUNT_ID)

        assertTrue(result.isNotEmpty(), "Transaction hex should not be empty")
        assertTrue(result.all { it in '0'..'9' || it in 'a'..'f' }, "Result should be valid hex")
    }

    @Test
    fun createTransaction_throwsWhenNoUtxos() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Btc, "BTC", null, 1, "m/84'/1'/1'")
        val provider = createProvider(emptyList(), destAccount = dest)
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        assertFailsWith<IllegalStateException> {
            provider.createTransaction(destAddress, amount, ACCOUNT_ID)
        }
    }

    @Test
    fun createTransaction_throwsWhenInsufficientFunds() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Btc, "BTC", null, 1, "m/84'/1'/1'")
        val provider = createProvider(listOf(testUtxo(amount = 100)), destAccount = dest)
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        val amount = BigDecimal.fromLong(1)

        assertFailsWith<IllegalStateException> {
            provider.createTransaction(destAddress, amount, ACCOUNT_ID)
        }
    }

    @Test
    fun createTransaction_isDeterministic() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Btc, "BTC", null, 1, "m/84'/1'/1'")
        val provider = createProvider(listOf(testUtxo()), destAccount = dest)
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        val result1 = provider.createTransaction(destAddress, amount, ACCOUNT_ID)
        val result2 = provider.createTransaction(destAddress, amount, ACCOUNT_ID)

        assertEquals(result1, result2)
    }

    @Test
    fun createTransaction_differentAmountsProduceDifferentTransactions() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Btc, "BTC", null, 1, "m/84'/1'/1'")
        val provider = createProvider(listOf(testUtxo()), destAccount = dest)
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)

        val result1 = provider.createTransaction(destAddress, BigDecimal.fromLong(1).divide(BigDecimal.fromLong(2000)), ACCOUNT_ID)
        val result2 = provider.createTransaction(destAddress, BigDecimal.fromLong(1).divide(BigDecimal.fromLong(4000)), ACCOUNT_ID)

        assertTrue(result1 != result2, "Different amounts should produce different transactions")
    }

    @Test
    fun createTransaction_selectsFromMultipleUtxos() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Btc, "BTC", null, 2, "m/84'/1'/2'")
        val provider = createProvider(listOf(
            testUtxo(id = 1, amount = 60_000),
            testUtxo(id = 2, amount = 60_000, derivationPath = "m/84'/1'/0'/0/1", txid = "b1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2")
        ), destAccount = dest)
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(2000))

        val result = provider.createTransaction(destAddress, amount, ACCOUNT_ID)

        assertTrue(result.isNotEmpty(), "Should produce a valid transaction using multiple UTXOs")
    }

    @Test
    fun estimateFee_usesDynamicFeeRateFromApi() = runTest {
        val provider = createProvider(utxos = listOf(testUtxo(amount = 200_000)))
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))
        val fee = provider.estimateFee(ACCOUNT_ID, amount)
        // With mock hourFee=5, 1 input, 2 outputs: vSize = 141, fee = 141 * 5 = 705 sat
        val expectedFeeBtc = BigDecimal.fromLong(705L).divide(BigDecimal.fromLong(100_000_000))
        assertEquals(expectedFeeBtc.toPlainString(), fee.totalCost.toPlainString())
    }

    @Test
    fun estimateFee_fallsBackToFallbackRate_onApiFailure() = runTest {
        val errorFactory: () -> HttpClient = {
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        respond(
                            content = "internal server error",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf("Content-Type", "text/plain")
                        )
                    }
                }
            }
        }
        val provider = createProvider(
            utxos = listOf(testUtxo(amount = 200_000)),
            createClient = errorFactory
        )
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))
        val fee = provider.estimateFee(ACCOUNT_ID, amount)
        // With fallback rate=10, 1 input, 2 outputs: vSize = 141, fee = 141 * 10 = 1410 sat
        val expectedFeeBtc = BigDecimal.fromLong(1410L).divide(BigDecimal.fromLong(100_000_000))
        assertEquals(expectedFeeBtc.toPlainString(), fee.totalCost.toPlainString())
    }

    @Test
    fun estimateFee_throwsWhenNoUtxos() = runTest {
        val provider = createProvider(emptyList())
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        assertFailsWith<IllegalStateException> {
            provider.estimateFee(ACCOUNT_ID, amount)
        }
    }

    @Test
    fun estimateFee_throwsWhenInsufficientFunds() = runTest {
        val provider = createProvider(listOf(testUtxo(amount = 100)))
        val amount = BigDecimal.fromLong(1) // 1 BTC but only 100 sat available

        assertFailsWith<IllegalStateException> {
            provider.estimateFee(ACCOUNT_ID, amount)
        }
    }

    @Test
    fun createTransaction_throwsWhenAmountExceedsMax() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Btc, "BTC", null, 1, "m/84'/1'/1'")
        val provider = createProvider(listOf(testUtxo(amount = Long.MAX_VALUE)), destAccount = dest)
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        // Long.MAX_VALUE BTC is way beyond representable
        val hugeAmount = BigDecimal.fromLong(Long.MAX_VALUE)

        assertFailsWith<IllegalArgumentException> {
            provider.createTransaction(destAddress, hugeAmount, ACCOUNT_ID)
        }
    }
}

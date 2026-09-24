package com.ultrabytecoder.coinsafebox.providers

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.providers.ton.TonProvider
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TonProviderCreateTransactionTest {

    companion object {
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val ACCOUNT_ID = "test-account-id"
        private const val DEST_ACCOUNT_ID = "dest-account"
    }

    private fun testAccount(index: Long = 0) = AccountInfo(
        id = ACCOUNT_ID, walletId = 1, name = "Test", amount = "0",
        type = AccountType.Ton(), symbol = "GRAM", address = null, accountIndex = index,
        derivationPath = "m/44'/607'/$index'"
    )

    private fun createProvider(
        account: AccountInfo = testAccount(),
        destAccount: AccountInfo? = null,
        params: JsonObject = JsonObject(emptyMap())
    ): TonProvider {
        val accounts = mutableMapOf(ACCOUNT_ID to account)
        destAccount?.let { accounts[it.id] = it }
        val mockClient: () -> HttpClient = {
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val response = when {
                            request.url.encodedPath.contains("getAddressState") ->
                                """{"ok":true,"result":"uninit"}"""
                            request.url.encodedPath.contains("getWalletInformation") ->
                                """{"ok":true,"result":{"wallet":true,"seqno":0}}"""
                            else ->
                                """{"ok":true,"result":"ok"}"""
                        }
                        respond(
                            content = response,
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "application/json")
                        )
                    }
                }
            }
        }
        return TonProvider(
            Hex.decode(SEED_HEX),
            FakeAccountRepository(accounts),
            params,
            NetworkConfig.testnet("test-api-key"),
            FakeTransactionRepository(),
            mockClient
        )
    }

    private fun v4Params(): JsonObject = JsonObject(mapOf("walletVersion" to JsonPrimitive("V4R2")))

    @Test
    fun createTransaction_returnsNonEmptyString() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Ton(), "GRAM", null, 1, "m/44'/607'/1'")
        val provider = createProvider(destAccount = dest)
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        val result = provider.createTransaction(destAddress, amount, ACCOUNT_ID)

        assertTrue(result.isNotEmpty(), "BOC base64 should not be empty")
    }

    @Test
    fun createTransaction_returnsValidBase64() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Ton(), "GRAM", null, 1, "m/44'/607'/1'")
        val provider = createProvider(destAccount = dest)
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        val result = provider.createTransaction(destAddress, amount, ACCOUNT_ID)

        val base64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        assertTrue(result.all { it in base64Chars }, "Should be valid standard base64 with padding")
    }

    @Test
    fun createTransaction_isDeterministic() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Ton(), "GRAM", null, 1, "m/44'/607'/1'")
        val provider = createProvider(destAccount = dest)
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        val result1 = provider.createTransaction(destAddress, amount, ACCOUNT_ID)
        val result2 = provider.createTransaction(destAddress, amount, ACCOUNT_ID)

        assertEquals(result1, result2)
    }

    @Test
    fun createTransaction_differentAmountsProduceDifferentResults() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Ton(), "GRAM", null, 1, "m/44'/607'/1'")
        val provider = createProvider(destAccount = dest)
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)

        val result1 = provider.createTransaction(destAddress, BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000)), ACCOUNT_ID)
        val result2 = provider.createTransaction(destAddress, BigDecimal.fromLong(1).divide(BigDecimal.fromLong(2000)), ACCOUNT_ID)

        assertNotEquals(result1, result2)
    }

    @Test
    fun createTransaction_differentAddressesProduceDifferentResults() = runTest {
        val dest1 = AccountInfo("dest1", 1, "Dest1", "0", AccountType.Ton(), "GRAM", null, 1, "m/44'/607'/1'")
        val dest2 = AccountInfo("dest2", 1, "Dest2", "0", AccountType.Ton(), "GRAM", null, 2, "m/44'/607'/2'")
        val provider = createProvider(destAccount = dest1)
        val provider2 = createProvider(destAccount = dest2)
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        val result1 = provider.createTransaction(provider.getAddress("dest1"), amount, ACCOUNT_ID)
        val result2 = provider2.createTransaction(provider2.getAddress("dest2"), amount, ACCOUNT_ID)

        assertNotEquals(result1, result2)
    }

    @Test
    fun v4_createTransaction_returnsNonEmptyString() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Ton(), "GRAM", null, 1, "m/44'/607'/1'")
        val provider = createProvider(destAccount = dest, params = v4Params())
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        val result = provider.createTransaction(destAddress, amount, ACCOUNT_ID)

        assertTrue(result.isNotEmpty(), "V4 BOC base64 should not be empty")
    }

    @Test
    fun v4_and_v3_produceDifferentTransactions() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Ton(), "GRAM", null, 1, "m/44'/607'/1'")
        val v3Provider = createProvider(destAccount = dest)
        val v4Provider = createProvider(destAccount = dest, params = v4Params())
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        val v3DestAddress = v3Provider.getAddress(DEST_ACCOUNT_ID)
        val v4DestAddress = v4Provider.getAddress(DEST_ACCOUNT_ID)

        // V3 and V4 wallets have different addresses for the same key
        assertNotEquals(v3DestAddress, v4DestAddress)

        val v3Result = v3Provider.createTransaction(v3DestAddress, amount, ACCOUNT_ID)
        val v4Result = v4Provider.createTransaction(v4DestAddress, amount, ACCOUNT_ID)

        // The resulting BOC should differ (different wallet contract code + signing message layout)
        assertNotEquals(v3Result, v4Result)
    }

    @Test
    fun v4_getAddress_differsFromV3() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Ton(), "GRAM", null, 1, "m/44'/607'/1'")
        val v3Provider = createProvider(destAccount = dest)
        val v4Provider = createProvider(destAccount = dest, params = v4Params())

        val v3Address = v3Provider.getAddress(DEST_ACCOUNT_ID)
        val v4Address = v4Provider.getAddress(DEST_ACCOUNT_ID)

        assertNotEquals(v3Address, v4Address)
    }

    @Test
    fun v4_createTransaction_isDeterministic() = runTest {
        val dest = AccountInfo(DEST_ACCOUNT_ID, 1, "Dest", "0", AccountType.Ton(), "GRAM", null, 1, "m/44'/607'/1'")
        val provider = createProvider(destAccount = dest, params = v4Params())
        val destAddress = provider.getAddress(DEST_ACCOUNT_ID)
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))

        val result1 = provider.createTransaction(destAddress, amount, ACCOUNT_ID)
        val result2 = provider.createTransaction(destAddress, amount, ACCOUNT_ID)

        assertEquals(result1, result2)
    }
}

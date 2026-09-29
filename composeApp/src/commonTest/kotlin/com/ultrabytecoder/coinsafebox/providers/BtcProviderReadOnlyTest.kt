package com.ultrabytecoder.coinsafebox.providers

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams
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

/**
 * Read-only BTC provider (Design A): built from an account xpub instead of the
 * master key. Verifies the constructor contract, that `createTransaction` can
 * never sign, and that the key-free read paths (getAddress / balance /
 * estimateFee) still work — with the xpub-derived address byte-matching the
 * full (private) key's address.
 */
class BtcProviderReadOnlyTest {

    companion object {
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val ACCOUNT_ID = "test-account-id"
        private const val ACCOUNT_PATH = "m/84'/1'/0'"
    }

    private val networkConfig = NetworkConfig.testnet("test-api-key")
    private val masterKey: DeterministicWallet.ExtendedPrivateKey =
        DeterministicWallet.generate(Hex.decode(SEED_HEX))

    private val accountXpub: DeterministicWallet.ExtendedPublicKey =
        BtcXpub.decode(BtcXpub.fromMasterKey(masterKey, ACCOUNT_PATH, testnet = true))

    private val account = AccountInfo(
        id = ACCOUNT_ID, walletId = 1, name = "Test", amount = "0",
        type = AccountType.Btc, symbol = "BTC", address = null, accountIndex = 0,
        derivationPath = ACCOUNT_PATH
    )

    private val mockClient: () -> HttpClient = {
        HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = """{"fastestFee":10,"halfHourFee":8,"hourFee":5,"economyFee":3,"minimumFee":1}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
            }
        }
    }

    private fun fullProvider(utxos: List<UtxoInfo> = emptyList()): BtcProvider =
        BtcProvider(
            masterKey, null, FakeUtxoRepository(utxos),
            FakeAccountRepository(mapOf(ACCOUNT_ID to account)), JsonObject(emptyMap()),
            networkConfig, FakeTransactionRepository(), mockClient
        )

    private fun readOnlyProvider(utxos: List<UtxoInfo> = emptyList()): BtcProvider =
        BtcProvider(
            null, accountXpub, FakeUtxoRepository(utxos),
            FakeAccountRepository(mapOf(ACCOUNT_ID to account)), JsonObject(emptyMap()),
            networkConfig, FakeTransactionRepository(), mockClient
        )

    private fun testUtxo(id: Long, amount: Long) = UtxoInfo(
        id = id, accountId = ACCOUNT_ID, derivationPath = "m/84'/1'/0'/0/0",
        amount = amount, txid = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2", vout = 0
    )

    @Test
    fun isReadOnly_isTrueForXpubProvider() {
        assertTrue(readOnlyProvider().isReadOnly)
        assertEquals(false, fullProvider().isReadOnly)
    }

    @Test
    fun ctor_requiresExactlyOneOfMasterKeyOrXpub() {
        assertFailsWith<IllegalArgumentException> {
            BtcProvider(
                masterKey, accountXpub, FakeUtxoRepository(),
                FakeAccountRepository(), JsonObject(emptyMap()), networkConfig,
                FakeTransactionRepository(), mockClient
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BtcProvider(
                null, null, FakeUtxoRepository(),
                FakeAccountRepository(), JsonObject(emptyMap()), networkConfig,
                FakeTransactionRepository(), mockClient
            )
        }
    }

    @Test
    fun getAddress_matchesFullModeByteForByte() = runTest {
        val full = fullProvider()
        val readOnly = readOnlyProvider()
        val fullAddress = full.getAddress(ACCOUNT_ID)
        val readOnlyAddress = readOnly.getAddress(ACCOUNT_ID)
        assertEquals(fullAddress, readOnlyAddress, "xpub-derived address must match the private-derived address")
        assertTrue(readOnlyAddress.isNotEmpty())
    }

    @Test
    fun createTransaction_throwsReadOnlyAndNeverSigns() = runTest {
        val readOnly = readOnlyProvider(listOf(testUtxo(1, 200_000)))
        assertFailsWith<ReadOnlyException> {
            readOnly.createTransaction("bc1qdestination", BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000)), ACCOUNT_ID)
        }
    }

    @Test
    fun balance_readsUtxosKeyFree() = runTest {
        val readOnly = readOnlyProvider(listOf(testUtxo(1, 120_000), testUtxo(2, 80_000)))
        val balance = readOnly.balance(ACCOUNT_ID)
        assertEquals(BigDecimal.fromLong(200_000), balance, "read-only balance is a pure read of the utxos table")
    }

    @Test
    fun estimateFee_worksKeyFreeWithExplicitFeeRate() = runTest {
        val readOnly = readOnlyProvider(listOf(testUtxo(1, 200_000)))
        val amount = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))
        val fee = readOnly.estimateFee(ACCOUNT_ID, amount, null, CustomFeeParams.Btc(10L))
        assertTrue(fee.totalCost > BigDecimal.ZERO, "read-only fee estimate should be positive")
    }
}

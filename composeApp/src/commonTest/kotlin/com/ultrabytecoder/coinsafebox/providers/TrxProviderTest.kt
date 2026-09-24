package com.ultrabytecoder.coinsafebox.providers

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.providers.tron.TrxProvider
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TrxProviderTest {

    companion object {
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val ACCOUNT_ID = "test-account"
    }

    private fun testAccount(index: Long = 0) = AccountInfo(
        id = ACCOUNT_ID, walletId = 1, name = "Test", amount = "0",
        type = AccountType.Trx, symbol = "TRX", address = null, accountIndex = index,
        derivationPath = "m/44'/195'/$index'/0/0"
    )

    private fun createProvider(account: AccountInfo = testAccount()): TrxProvider {
        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))
        return TrxProvider(masterKey, FakeAccountRepository(mapOf(ACCOUNT_ID to account)), JsonObject(emptyMap()), NetworkConfig.testnet("test-api-key"), FakeTransactionRepository())
    }

    @Test
    fun getAddress_startsWithT() = runTest {
        val address = createProvider().getAddress(ACCOUNT_ID)
        assertTrue(address.startsWith("T"), "TRON address should start with 'T'")
    }

    @Test
    fun getAddress_isBase58() = runTest {
        val address = createProvider().getAddress(ACCOUNT_ID)
        assertTrue(address.all { it in '1'..'9' || it in 'A'..'Z' || it in 'a'..'z' },
            "TRON address should be Base58")
    }

    @Test
    fun getAddress_isDeterministic() = runTest {
        val provider = createProvider()
        assertEquals(provider.getAddress(ACCOUNT_ID), provider.getAddress(ACCOUNT_ID))
    }

    @Test
    fun getAddress_differentIndicesReturnDifferentAddresses() = runTest {
        val provider0 = createProvider(testAccount(index = 0))
        val provider1 = createProvider(testAccount(index = 1))
        assertNotEquals(provider0.getAddress(ACCOUNT_ID), provider1.getAddress(ACCOUNT_ID))
    }
}

package com.ultrabytecoder.coinsafebox.providers

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EthProviderTest {

    companion object {
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val ACCOUNT_ID = "test-account"
    }

    private fun testAccount(index: Long = 0) = AccountInfo(
        id = ACCOUNT_ID, walletId = 1, name = "Test", amount = "0",
        type = AccountType.Eth, symbol = "ETH", address = null, accountIndex = index,
        derivationPath = "m/44'/60'/$index'/0/0"
    )

    private fun createProvider(account: AccountInfo = testAccount()): EthProvider {
        val masterKey = DeterministicWallet.generate(Hex.decode(SEED_HEX))
        return EthProvider(
            masterKey,
            FakeAccountRepository(mapOf(ACCOUNT_ID to account)),
            JsonObject(emptyMap()),
            NetworkConfig.testnet("test-api-key"),
            transactionRepository = FakeTransactionRepository()
        )
    }

    @Test
    fun getAddress_returnsCorrectAddressForKnownSeed() = runTest {
        val address = createProvider().getAddress(ACCOUNT_ID)
        assertEquals("0x9858effd232b4033e47d90003d41ec34ecaeda94", address)
    }

    @Test
    fun getAddress_hasOxPrefix() = runTest {
        assertTrue(createProvider().getAddress(ACCOUNT_ID).startsWith("0x"))
    }

    @Test
    fun getAddress_is42Characters() = runTest {
        assertEquals(42, createProvider().getAddress(ACCOUNT_ID).length)
    }

    @Test
    fun getAddress_containsOnlyValidHexAfterPrefix() = runTest {
        val address = createProvider().getAddress(ACCOUNT_ID)
        val hex = address.removePrefix("0x")
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
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

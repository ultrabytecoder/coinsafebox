package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ultrabytecoder.coinsafebox.data.KeyProviderImpl
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.domain.exception.WrongMnemonicException
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.WalletInfo
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import com.ultrabytecoder.coinsafebox.providers.BtcProvider
import com.ultrabytecoder.coinsafebox.providers.FakeAccountRepository
import com.ultrabytecoder.coinsafebox.providers.FakeTransactionRepository
import com.ultrabytecoder.coinsafebox.providers.FakeUtxoRepository
import com.ultrabytecoder.coinsafebox.security.SecureMnemonicCode
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.secp256k1.Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Read-only send guard: the wrong-mnemonic check (which also catches a wrong
 * BIP-39 passphrase, since the passphrase feeds seed derivation) must reject a
 * non-matching phrase BEFORE any signing happens — and a matching phrase must
 * get past the guard (failing only later, at `createTransaction`, on the
 * fixture's empty UTXO set).
 *
 * Uses a real BTC provider (built offline from the derived seed) so the
 * guard's address comparison is exercised end-to-end without any network.
 */
class SendUseCaseReadOnlyTest {

    companion object {
        // Seed for "abandon ... about" (empty passphrase).
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val CORRECT_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        // A different valid BIP-39 phrase -> a different seed -> a different address.
        private const val WRONG_MNEMONIC =
            "all all all all all all all all all all all all"
        private const val ACCOUNT_ID = "acct-1"
        private const val ACCOUNT_PATH = "m/84'/1'/0'"
        private val AMOUNT = BigDecimal.fromLong(1).divide(BigDecimal.fromLong(1000))
    }

    private val networkConfig = NetworkConfig.testnet("test-api-key")

    private class FakeWalletRepository(private val wallet: WalletInfo?, private val seed: ByteArray? = null) : WalletRepository {
        override fun getWalletsFlow(): Flow<List<WalletInfo>> = flowOf(wallet?.let { listOf(it) } ?: emptyList())
        override suspend fun getWallet(id: Long): WalletInfo? = wallet
        override suspend fun getMasterSeed(id: Long): ByteArray? = seed
        override suspend fun insertWallet(name: String, masterSeed: ByteArray, mnemonic: ByteArray?, hasPassphrase: Boolean): Long = 1
        override suspend fun deleteWallet(id: Long) {}
        override suspend fun getStoredMnemonic(id: Long): ByteArray? = null
        override suspend fun renameWallet(id: Long, name: String) {}
        override suspend fun clearMasterKey(id: Long) {}
        override suspend fun restoreMasterKey(id: Long, masterSeed: ByteArray, mnemonic: ByteArray?) {}
    }

    private val readOnlyWallet = WalletInfo(1L, "w", isReadOnly = true, hasPassphrase = false)
    private val fullWallet = WalletInfo(1L, "w", isReadOnly = false, hasPassphrase = false)

    private suspend fun btcAddressFor(seed: ByteArray, account: AccountInfo): String {
        val provider = BtcProvider(
            DeterministicWallet.generate(seed), null,
            FakeUtxoRepository(), FakeAccountRepository(mapOf(account.id to account)),
            JsonObject(emptyMap()), networkConfig, FakeTransactionRepository(),
            { HttpClient(MockEngine) { engine { addHandler { respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json")) } } } }
        )
        return provider.getAddress(account.id)
    }

    private fun baseAccount(address: String) = AccountInfo(
        id = ACCOUNT_ID, walletId = 1, name = "Test", amount = "0",
        type = AccountType.Btc, symbol = "BTC", address = address, accountIndex = 0,
        derivationPath = ACCOUNT_PATH
    )

    private fun useCase(account: AccountInfo, wallet: WalletInfo, seed: ByteArray? = null) = SendUseCase(
        FakeAccountRepository(mapOf(account.id to account)),
        FakeUtxoRepository(emptyList()), // no UTXOs: createTransaction fails AFTER the guard
        FakeTransactionRepository(),
        KeyProviderImpl(FakeWalletRepository(wallet, seed)),
        networkConfig,
        FakeWalletRepository(wallet, seed)
    )

    @Test
    fun readOnly_missingMnemonicIsRejected() = runTest {
        val account = baseAccount(btcAddressFor(Hex.decode(SEED_HEX), baseAccount("x")))
        val useCase = useCase(account, readOnlyWallet)
        assertFailsWith<IllegalArgumentException> {
            useCase(ACCOUNT_ID, "bc1qdest", AMOUNT) // no mnemonic supplied
        }
    }

    @Test
    fun readOnly_passphraseRequiredWhenWalletHasPassphrase() = runTest {
        val wallet = WalletInfo(1L, "w", isReadOnly = true, hasPassphrase = true)
        val account = baseAccount("bc1qstored")
        val useCase = useCase(account, wallet)
        assertFailsWith<IllegalArgumentException> {
            useCase(ACCOUNT_ID, "bc1qdest", AMOUNT, null, CORRECT_MNEMONIC.toCharArray(), CharArray(0))
        }
    }

    @Test
    fun readOnly_wrongMnemonicThrowsBeforeSigning() = runTest {
        val correctAddress = btcAddressFor(Hex.decode(SEED_HEX), baseAccount("x"))
        val account = baseAccount(correctAddress) // stored address matches the CORRECT phrase
        val useCase = useCase(account, readOnlyWallet)
        assertFailsWith<WrongMnemonicException> {
            useCase(ACCOUNT_ID, "bc1qdest", AMOUNT, null, WRONG_MNEMONIC.toCharArray(), CharArray(0))
        }
    }

    @Test
    fun readOnly_correctMnemonicPassesTheGuard() = runTest {
        val correctAddress = btcAddressFor(Hex.decode(SEED_HEX), baseAccount("x"))
        val account = baseAccount(correctAddress)
        val useCase = useCase(account, readOnlyWallet)
        // The guard accepts the right phrase, so failure (if any) comes later at
        // createTransaction on the empty UTXO set — NOT a WrongMnemonicException.
        assertFailsWith<IllegalStateException> {
            useCase(ACCOUNT_ID, "bc1qdest", AMOUNT, null, CORRECT_MNEMONIC.toCharArray(), CharArray(0))
        }
    }

    @Test
    fun readOnly_wrongPassphraseThrowsWhenWalletHasPassphrase() = runTest {
        val wallet = WalletInfo(1L, "w", isReadOnly = true, hasPassphrase = true)
        // Stored address derived with passphrase "CORRECT".
        val seedWithCorrectPass = SecureMnemonicCode.toSeed(CORRECT_MNEMONIC.toCharArray(), "CORRECT".toCharArray())
        val storedAddress = btcAddressFor(seedWithCorrectPass, baseAccount("x"))
        seedWithCorrectPass.fill(0)
        val account = baseAccount(storedAddress)
        val useCase = useCase(account, wallet)
        // A different passphrase derives a different seed -> different address -> guard rejects.
        assertFailsWith<WrongMnemonicException> {
            useCase(ACCOUNT_ID, "bc1qdest", AMOUNT, null, CORRECT_MNEMONIC.toCharArray(), "WRONG".toCharArray())
        }
    }

    @Test
    fun readOnly_correctPassphrasePassesTheGuard() = runTest {
        val wallet = WalletInfo(1L, "w", isReadOnly = true, hasPassphrase = true)
        val seedWithCorrectPass = SecureMnemonicCode.toSeed(CORRECT_MNEMONIC.toCharArray(), "CORRECT".toCharArray())
        val storedAddress = btcAddressFor(seedWithCorrectPass, baseAccount("x"))
        seedWithCorrectPass.fill(0)
        val account = baseAccount(storedAddress)
        val useCase = useCase(account, wallet)
        assertFailsWith<IllegalStateException> {
            useCase(ACCOUNT_ID, "bc1qdest", AMOUNT, null, CORRECT_MNEMONIC.toCharArray(), "CORRECT".toCharArray())
        }
    }

    @Test
    fun sendFull_pathIsUnchangedAndIgnoresMnemonic() = runTest {
        val correctAddress = btcAddressFor(Hex.decode(SEED_HEX), baseAccount("x"))
        val account = baseAccount(correctAddress)
        // Full wallet: withMasterSeed path, mnemonic is irrelevant.
        val useCase = useCase(account, fullWallet, seed = Hex.decode(SEED_HEX))
        // Fails at createTransaction (no UTXOs) — proving the full path ran, not the read-only guard.
        assertFailsWith<IllegalStateException> {
            useCase(ACCOUNT_ID, "bc1qdest", AMOUNT)
        }
    }
}

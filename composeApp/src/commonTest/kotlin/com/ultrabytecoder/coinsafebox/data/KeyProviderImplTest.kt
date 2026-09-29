package com.ultrabytecoder.coinsafebox.data

import com.ultrabytecoder.coinsafebox.domain.exception.ReadOnlyWalletException
import com.ultrabytecoder.coinsafebox.domain.model.WalletInfo
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import com.ultrabytecoder.coinsafebox.security.SecureMnemonicCode
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the loan/wipe discipline of [KeyProviderImpl]:
 *  - [KeyProviderImpl.withMasterSeed] loans the stored seed and wipes it, and
 *    refuses (throws) for a read-only wallet.
 *  - [KeyProviderImpl.withTransientSeed] derives a transient seed from the
 *    mnemonic, loans it, and wipes seed + mnemonic + passphrase in `finally` —
 *    including on validation failure and on a throwing block. A bad-checksum
 *    mnemonic must be rejected BEFORE the block ever runs.
 */
class KeyProviderImplTest {

    companion object {
        private const val SEED_HEX =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        private const val VALID_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }

    private class FakeWalletRepository(
        private val wallet: WalletInfo?,
        private val seed: ByteArray? = null
    ) : WalletRepository {
        override fun getWalletsFlow(): Flow<List<WalletInfo>> =
            flowOf(wallet?.let { listOf(it) } ?: emptyList())
        override suspend fun getWallet(id: Long): WalletInfo? = wallet
        override suspend fun getMasterSeed(id: Long): ByteArray? = seed
        override suspend fun insertWallet(name: String, masterSeed: ByteArray, mnemonic: ByteArray?, hasPassphrase: Boolean): Long = 1
        override suspend fun deleteWallet(id: Long) {}
        override suspend fun getStoredMnemonic(id: Long): ByteArray? = null
        override suspend fun renameWallet(id: Long, name: String) {}
        override suspend fun clearMasterKey(id: Long) {}
        override suspend fun restoreMasterKey(id: Long, masterSeed: ByteArray, mnemonic: ByteArray?) {}
    }

    private fun fullWallet() = WalletInfo(1L, "w", isReadOnly = false, hasPassphrase = false)
    private fun readOnlyWallet() = WalletInfo(1L, "w", isReadOnly = true, hasPassphrase = false)

    // ---- withMasterSeed ----

    @Test
    fun withMasterSeed_fullWalletLoansSeedThenWipesIt() = runTest {
        val seed = Hex.decode(SEED_HEX)
        val repo = FakeWalletRepository(fullWallet(), seed)
        val provider = KeyProviderImpl(repo)

        var loanedRef: ByteArray? = null
        var loanedCopy: ByteArray? = null
        val result = provider.withMasterSeed(1L) { s ->
            loanedRef = s
            loanedCopy = s.copyOf()
            "signed"
        }
        assertEquals("signed", result)
        assertContentEquals(Hex.decode(SEED_HEX), loanedCopy!!, "loaned seed must be the stored seed")
        // The very array the block received is wiped after the loan.
        assertTrue(loanedRef!!.all { it == 0.toByte() }, "loaned master seed must be wiped after the block")
    }

    @Test
    fun withMasterSeed_readOnlyWalletThrowsAndNeverLoans() = runTest {
        val repo = FakeWalletRepository(readOnlyWallet(), Hex.decode(SEED_HEX))
        val provider = KeyProviderImpl(repo)
        var blockRan = false
        assertFailsWith<ReadOnlyWalletException> {
            provider.withMasterSeed(1L) { blockRan = true; "x" }
        }
        assertFalse(blockRan, "block must not run for a read-only wallet")
    }

    @Test
    fun withMasterSeed_missingWalletThrows() = runTest {
        val provider = KeyProviderImpl(FakeWalletRepository(null))
        assertFailsWith<IllegalArgumentException> {
            provider.withMasterSeed(99L) { "x" }
        }
    }

    @Test
    fun withMasterSeed_fullWalletWithoutSeedThrows() = runTest {
        val provider = KeyProviderImpl(FakeWalletRepository(fullWallet(), null))
        assertFailsWith<IllegalStateException> {
            provider.withMasterSeed(1L) { "x" }
        }
    }

    // ---- withTransientSeed ----

    @Test
    fun withTransientSeed_validMnemonicLoansKnownSeedThenWipesEverything() = runTest {
        val provider = KeyProviderImpl(FakeWalletRepository(null))
        val mnemonic = VALID_MNEMONIC.toCharArray()
        val passphrase = CharArray(0)

        var loaned: ByteArray? = null
        var loanedCopy: ByteArray? = null
        val result = provider.withTransientSeed(mnemonic, passphrase) { s ->
            loaned = s
            loanedCopy = s.copyOf() // capture before the finally-block wipe
            s.size
        }
        assertEquals(64, result, "BIP-39 seed must be 64 bytes")
        // The transient seed must equal the canonical BIP-39 vector for this phrase.
        assertContentEquals(Hex.decode(SEED_HEX), loanedCopy!!, "derived seed must match the BIP-39 vector")
        // After the loan, seed + mnemonic + passphrase are all wiped.
        assertTrue(loaned!!.all { it == 0.toByte() }, "transient seed must be wiped")
        assertTrue(mnemonic.all { it == '\u0000' }, "mnemonic must be wiped")
        assertTrue(passphrase.all { it == '\u0000' } || passphrase.isEmpty(), "passphrase must be wiped")
    }

    @Test
    fun withTransientSeed_passphraseChangesSeed() = runTest {
        val provider = KeyProviderImpl(FakeWalletRepository(null))
        val mnemonic = VALID_MNEMONIC.toCharArray()
        var withPass: ByteArray? = null
        provider.withTransientSeed(mnemonic, "TREZOR".toCharArray()) { withPass = it.copyOf() }
        assertTrue(withPass!!.all { it == 0.toByte() }.not(), "sanity: captured a non-zero seed")
        // A different passphrase must produce a different seed.
        val mnemonic2 = VALID_MNEMONIC.toCharArray()
        var withoutPass: ByteArray? = null
        provider.withTransientSeed(mnemonic2, CharArray(0)) { withoutPass = it.copyOf() }
        assertFalse(
            withPass!!.contentEquals(withoutPass!!),
            "a non-empty passphrase must change the derived seed"
        )
    }

    @Test
    fun withTransientSeed_badChecksumThrowsBeforeBlock() = runTest {
        val provider = KeyProviderImpl(FakeWalletRepository(null))
        // 12 x "abandon" is a valid word count but has an invalid checksum.
        val bad = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon".toCharArray()
        var blockRan = false
        assertFailsWith<IllegalArgumentException> {
            provider.withTransientSeed(bad, CharArray(0)) { blockRan = true }
        }
        assertFalse(blockRan, "block must not run when the mnemonic fails validation")
        assertTrue(bad.all { it == '\u0000' }, "mnemonic must be wiped even on validation failure")
    }

    @Test
    fun withTransientSeed_blockThrowsStillWipesMnemonic() = runTest {
        val provider = KeyProviderImpl(FakeWalletRepository(null))
        val mnemonic = VALID_MNEMONIC.toCharArray()
        assertFailsWith<RuntimeException> {
            provider.withTransientSeed(mnemonic, CharArray(0)) {
                throw RuntimeException("boom")
            }
        }
        assertTrue(mnemonic.all { it == '\u0000' }, "mnemonic must be wiped when the block throws")
    }

    @Test
    fun toSeedMatchesMnemonicCodeVector() {
        val seed = SecureMnemonicCode.toSeed(VALID_MNEMONIC.toCharArray(), CharArray(0))
        try {
            assertContentEquals(Hex.decode(SEED_HEX), seed)
        } finally {
            seed.fill(0)
        }
    }
}

package com.ultrabytecoder.coinsafebox.security

import com.ultrabytecoder.coinsafebox.data.DatabaseProvider
import com.ultrabytecoder.coinsafebox.data.ExistingKeyMaterialException
import com.ultrabytecoder.coinsafebox.data.PinRepositoryImpl
import com.ultrabytecoder.coinsafebox.data.SettingsStore
import com.ultrabytecoder.coinsafebox.db.CoinSafeBoxDatabase
import com.ultrabytecoder.coinsafebox.domain.model.WalletInfo
import com.ultrabytecoder.coinsafebox.domain.repository.PinState
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * F-2 regression: a missing PIN lockout state must never be treated as "fresh
 * install" while DEK key material exists — a fresh setup would deleteAll() the
 * envelope and destroy the wallet. The repository must surface the corrupted
 * (recovery) state, and setupPin must refuse without an explicit recovery
 * acknowledgement.
 */
class PinRepositoryGuardTest {

    private class InMemorySettingsStorage : SettingsStore {
        private val map = HashMap<String, String>()
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getString(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
    }

    private class NoopWalletRepository : WalletRepository {
        override fun getWalletsFlow(): Flow<List<WalletInfo>> = kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun getWallet(id: Long): WalletInfo? = null
        override suspend fun getMasterSeed(id: Long): ByteArray? = null
        override suspend fun insertWallet(name: String, masterSeed: ByteArray, mnemonic: ByteArray?): Long = 0
        override suspend fun deleteWallet(id: Long) {}
        override suspend fun getStoredMnemonic(id: Long): ByteArray? = null
        override suspend fun renameWallet(id: Long, name: String) {}
    }

    private class FakeSessionManager : com.ultrabytecoder.coinsafebox.data.SessionUnlocker {
        override suspend fun unlock(dek: ByteArray): com.ultrabytecoder.coinsafebox.security.UnlockResult =
            com.ultrabytecoder.coinsafebox.security.UnlockResult.Success
        override suspend fun unlockRecreating(dek: ByteArray): Boolean = true
    }

    @Test
    fun envelopeWithoutPinDataIsCorruptedAndSetupRefused() = runTest {
        HardwareKeyStore.deleteKey()
        val storage = InMemorySettingsStorage()
        val keyManager = KeyManager(storage)
        // Create a DEK envelope directly (as if setup had completed), but write
        // NO pin_data — simulating lost/cleared lockout metadata over a wallet.
        val dek = keyManager.generateAndWrapDek("123456".toCharArray(), SecurityMethod.PIN)
        dek.fill(0)

        val sessionManager = FakeSessionManager()
        val repo = PinRepositoryImpl(storage, keyManager, sessionManager, NoopWalletRepository())

        // loadState runs async in the repository's init scope — wait for it.
        val state = repo.pinStateFlow.first { it !is PinState.Loading }
        assertIs<PinState.Setup>(state)
        assertEquals(true, state.isCorrupted, "envelope without pin_data must surface as corrupted")

        // Fresh setup (no recovery acknowledgement) must be REFUSED and must not
        // destroy the envelope.
        val pin = "654321".toCharArray()
        try {
            assertFailsWith<ExistingKeyMaterialException> {
                repo.setupPin(pin, SecurityMethod.PIN)
            }
        } finally {
            pin.fill('\u0000')
        }
        assertEquals(true, keyManager.hasRawEnvelope(), "refused setup must keep the envelope")

        // Acknowledged recovery proceeds (destroys the old material by design).
        val pin2 = "654321".toCharArray()
        try {
            repo.setupPin(pin2, SecurityMethod.PIN, recoveryAcknowledged = true)
        } finally {
            pin2.fill('\u0000')
        }
        assertEquals(true, keyManager.hasRawEnvelope(), "recovery setup writes a new envelope")
    }

    @Test
    fun freshInstallAllowsSetup() = runTest {
        HardwareKeyStore.deleteKey()
        val storage = InMemorySettingsStorage()
        val keyManager = KeyManager(storage)
        val repo = PinRepositoryImpl(storage, keyManager, FakeSessionManager(), NoopWalletRepository())

        val state = repo.pinStateFlow.first { it !is PinState.Loading }
        assertIs<PinState.NotSetup>(state)

        val pin = "123456".toCharArray()
        try {
            repo.setupPin(pin, SecurityMethod.PIN)
        } finally {
            pin.fill('\u0000')
        }
        assertEquals(true, keyManager.hasRawEnvelope())
    }
}

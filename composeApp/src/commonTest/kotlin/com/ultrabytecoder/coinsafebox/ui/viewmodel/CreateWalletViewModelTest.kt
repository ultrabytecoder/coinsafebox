package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.domain.model.WalletInfo
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import com.ultrabytecoder.coinsafebox.domain.usecase.CreateWalletUseCase
import com.ultrabytecoder.coinsafebox.security.EntropyCombiner
import com.ultrabytecoder.coinsafebox.security.SessionLockNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CreateWalletViewModel] covering:
 *  - fresh-start defaults + inactive draft
 *  - resume clamp table (REVEAL -> PASSPHRASE, others as-is)
 *  - non-secret state restoration from draft
 *  - secrets never restored (verified via behavior)
 *  - draft tracking (always-update, not monotonic-max; back() updates)
 *  - draft cleared on success (both code paths)
 *  - draft NOT cleared on create error
 *  - SESS-3 wipe on session lock (secrets wiped, non-secret state survives)
 *  - re-arm generation after lock + wipe
 *
 * Conventions (mirrors SetupPinViewModelTest):
 *  - [StandardTestDispatcher]; [Dispatchers.setMain] in @BeforeTest.
 *  - @AfterTest cancels every created VM's viewModelScope (onCleared never
 *    fires for remember-built VMs; a leaked SESS-3 collector would react to
 *    other tests' notifyLocked()).
 *  - [runTest] with the test dispatcher.
 *  - Fresh [CreateWalletFlowDraft] per test (direct construction, no Koin).
 *  - After constructing a VM, [StandardTestDispatcher.Scheduler.advanceUntilIdle]
 *    so the init { viewModelScope.launch { } } SESS-3 collector subscribes
 *    BEFORE any notifyLocked() call.
 *
 * The VM launches create/generate coroutines on [Dispatchers.Default], which
 * the test scheduler does NOT control. Suspending waits (`.first { }`) are
 * used instead of `advanceUntilIdle()` for that work: inside [runTest],
 * continuations dispatched from the real Default thread are processed by
 * runTest's event loop, so these waits are deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateWalletViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val createdVms = mutableListOf<CreateWalletViewModel>()

    /**
     * Fake [WalletRepository] for use with the REAL [CreateWalletUseCase].
     * Records insert args, returns a fixed id (42), and can throw on demand
     * to exercise the error path.
     */
    private class FakeWalletRepository : WalletRepository {
        var insertCallCount = 0
        var lastInsertedName: String? = null
        var lastInsertedMasterSeed: ByteArray? = null
        var lastInsertedMnemonic: ByteArray? = null
        var lastInsertedHasPassphrase: Boolean? = null
        var throwOnInsert: Throwable? = null

        override fun getWalletsFlow(): Flow<List<WalletInfo>> = flowOf(emptyList())
        override suspend fun getWallet(id: Long): WalletInfo? = null
        override suspend fun getMasterSeed(id: Long): ByteArray? = null
        override suspend fun insertWallet(
            name: String,
            masterSeed: ByteArray,
            mnemonic: ByteArray?,
            hasPassphrase: Boolean
        ): Long {
            insertCallCount++
            lastInsertedName = name
            lastInsertedMasterSeed = masterSeed
            lastInsertedMnemonic = mnemonic
            lastInsertedHasPassphrase = hasPassphrase
            throwOnInsert?.let { throw it }
            return 42L
        }

        override suspend fun deleteWallet(id: Long) {}
        override suspend fun getStoredMnemonic(id: Long): ByteArray? = null
        override suspend fun renameWallet(id: Long, name: String) {}
        override suspend fun clearMasterKey(id: Long) {}
        override suspend fun restoreMasterKey(id: Long, masterSeed: ByteArray, mnemonic: ByteArray?) {}
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        // Replicates rememberDisposableViewModel's onDispose: cancel the
        // scope so the SESS-3 collector does not leak across tests.
        createdVms.forEach { it.viewModelScope.cancel() }
        createdVms.clear()
        Dispatchers.resetMain()
    }

    /**
     * Constructs a VM with the given draft and repo, registers it for
     * @AfterTest cleanup, and advances the scheduler so the init SESS-3
     * collector is subscribed before any notifyLocked() call.
     */
    private fun createVm(
        draft: CreateWalletFlowDraft = CreateWalletFlowDraft(),
        repo: WalletRepository = FakeWalletRepository()
    ): CreateWalletViewModel {
        val useCase = CreateWalletUseCase(repo)
        val vm = CreateWalletViewModel(useCase, draft)
        createdVms.add(vm)
        // Let the init { viewModelScope.launch { } } SESS-3 collector
        // subscribe before any notifyLocked() call in tests.
        dispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Test
    fun freshStart_noDraft_defaultsAndInactiveDraft() = runTest(dispatcher) {
        val draft = CreateWalletFlowDraft()
        val viewModel = createVm(draft)

        assertEquals(CreateWalletViewModel.Step.SETUP, viewModel.step.value)
        assertEquals(CreateWalletViewModel.Mode.GENERATE_NEW, viewModel.mode.value)
        assertEquals(24, viewModel.wordCount.value)
        assertFalse(viewModel.useGesture.value)
        assertEquals("My wallet", viewModel.walletNameValue)
        assertNull(viewModel.finalMnemonic.value)
        assertFalse(viewModel.hasPassphrase)
        assertFalse(draft.active)
    }

    @Test
    fun resumeClampTable() = runTest(dispatcher) {
        val cases = listOf(
            CreateWalletViewModel.Step.SETUP to CreateWalletViewModel.Step.SETUP,
            CreateWalletViewModel.Step.GESTURE to CreateWalletViewModel.Step.GESTURE,
            CreateWalletViewModel.Step.PASSPHRASE to CreateWalletViewModel.Step.PASSPHRASE,
            CreateWalletViewModel.Step.REVEAL to CreateWalletViewModel.Step.PASSPHRASE
        )
        for ((lastStep, expectedStep) in cases) {
            val draft = CreateWalletFlowDraft()
            draft.begin(CreateWalletViewModel.Mode.GENERATE_NEW)
            draft.recordStep(lastStep)
            val viewModel = createVm(draft)

            assertEquals(
                expectedStep,
                viewModel.step.value,
                "lastStep=$lastStep should resume at $expectedStep"
            )
        }
    }

    @Test
    fun resumeRestoresNonSecretState() = runTest(dispatcher) {
        val draft = CreateWalletFlowDraft()
        draft.begin(CreateWalletViewModel.Mode.RESTORE_EXISTING)
        draft.updateSettings(
            mode = CreateWalletViewModel.Mode.RESTORE_EXISTING,
            wordCount = 21,
            useGesture = true,
            walletName = "Restored"
        )
        val viewModel = createVm(draft)

        assertEquals(CreateWalletViewModel.Mode.RESTORE_EXISTING, viewModel.mode.value)
        assertEquals(21, viewModel.wordCount.value)
        assertTrue(viewModel.useGesture.value)
        assertEquals("Restored", viewModel.walletNameValue)
    }

    @Test
    fun resumeNeverRestoresSecrets() = runTest(dispatcher) {
        val draft = CreateWalletFlowDraft()
        draft.begin(CreateWalletViewModel.Mode.GENERATE_NEW)
        draft.recordStep(CreateWalletViewModel.Step.PASSPHRASE)
        val viewModel = createVm(draft)

        // No passphrase, no mnemonic after resume.
        assertFalse(viewModel.hasPassphrase)

        // createWalletFromGenerated errors because no mnemonic was restored.
        viewModel.createWalletFromGenerated()
        val error = viewModel.createError.first { it != null }
        assertEquals("Mnemonic not generated", error)
    }

    @Test
    fun draftTracking_alwaysUpdates_noMonotonicMax() = runTest(dispatcher) {
        val draft = CreateWalletFlowDraft()
        val viewModel = createVm(draft)

        // Enable gesture so proceedFromSetup routes to GESTURE.
        viewModel.setUseGesture(true)
        assertEquals(CreateWalletViewModel.Step.SETUP, draft.lastStep)

        viewModel.proceedFromSetup()
        assertEquals(CreateWalletViewModel.Step.GESTURE, draft.lastStep)

        viewModel.proceedFromGesture()
        // PASSPHRASE(ordinal 1) < GESTURE(ordinal 2) — monotonic-max would
        // refuse this transition and leave lastStep at GESTURE.
        assertEquals(CreateWalletViewModel.Step.PASSPHRASE, draft.lastStep)

        // back() from PASSPHRASE (with gesture enabled) goes to GESTURE.
        viewModel.back()
        assertEquals(CreateWalletViewModel.Step.GESTURE, draft.lastStep)
    }

    @Test
    fun draftClearedOnCreateWalletFromMnemonicSuccess() = runTest(dispatcher) {
        val draft = CreateWalletFlowDraft()
        val repo = FakeWalletRepository()
        val viewModel = createVm(draft, repo)

        viewModel.setMode(CreateWalletViewModel.Mode.RESTORE_EXISTING)
        assertTrue(draft.active)

        val mnemonic = EntropyCombiner.generate(24, null)
        viewModel.createWalletFromMnemonic(mnemonic)

        // Wait for the Default-thread coroutine to emit walletCreated.
        val walletId = viewModel.walletCreated.first()
        assertEquals(42L, walletId)
        assertEquals(1, repo.insertCallCount)
        assertFalse(draft.active)
    }

    @Test
    fun draftClearedOnCreateWalletFromGeneratedSuccess() = runTest(dispatcher) {
        val draft = CreateWalletFlowDraft()
        val repo = FakeWalletRepository()
        val viewModel = createVm(draft, repo)

        // Activate the draft via any setter.
        viewModel.setWalletName("Generated Wallet")
        assertTrue(draft.active)

        // Generate a mnemonic (runs on Dispatchers.Default).
        viewModel.generateFinalMnemonic()
        viewModel.finalMnemonic.first { it != null }

        // Persist (runs on Dispatchers.Default).
        viewModel.createWalletFromGenerated()
        val walletId = viewModel.walletCreated.first()
        assertEquals(42L, walletId)
        assertFalse(draft.active)
    }

    @Test
    fun draftNotClearedOnCreateError() = runTest(dispatcher) {
        val draft = CreateWalletFlowDraft()
        val repo = FakeWalletRepository()
        repo.throwOnInsert = IllegalArgumentException("duplicate name")
        val viewModel = createVm(draft, repo)

        viewModel.setMode(CreateWalletViewModel.Mode.RESTORE_EXISTING)
        assertTrue(draft.active)

        val mnemonic = EntropyCombiner.generate(24, null)
        viewModel.createWalletFromMnemonic(mnemonic)

        // Wait for the Default-thread coroutine to set createError.
        val error = viewModel.createError.first { it != null }
        assertNotNull(error)
        assertTrue(error.isNotEmpty())
        // Draft must remain active so the user can retry.
        assertTrue(draft.active)
    }

    @Test
    fun wipeOnLock_sessionLockNotifier() = runTest(dispatcher) {
        val draft = CreateWalletFlowDraft()
        val viewModel = createVm(draft)

        viewModel.setPassphrase("secret".toCharArray())
        viewModel.storeGestureDigest(ByteArray(32) { it.toByte() })
        viewModel.generateFinalMnemonic()
        // Wait for the Default-thread generation to publish.
        viewModel.finalMnemonic.first { it != null }

        // Preconditions: secrets are live.
        assertTrue(viewModel.hasPassphrase)
        assertTrue(viewModel.finalMnemonic.value != null)

        // Lock the session.
        SessionLockNotifier.notifyLocked()
        // Advance the test scheduler so the SESS-3 collector (launched on
        // Main in init) fires wipeSecrets().
        dispatcher.scheduler.advanceUntilIdle()

        // Secrets wiped.
        assertFalse(viewModel.hasPassphrase)
        assertNull(viewModel.finalMnemonic.value)

        // Non-secret state survives the wipe.
        assertEquals("My wallet", viewModel.walletNameValue)
        assertEquals(CreateWalletViewModel.Step.SETUP, viewModel.step.value)
    }

    @Test
    fun reArmAfterLock_freshMnemonicPublished() = runTest(dispatcher) {
        val draft = CreateWalletFlowDraft()
        val viewModel = createVm(draft)

        // Generate, then lock + wipe.
        viewModel.generateFinalMnemonic()
        viewModel.finalMnemonic.first { it != null }

        SessionLockNotifier.notifyLocked()
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.finalMnemonic.value)

        // Re-arm: generateFinalMnemonic() again should launch a fresh job
        // and eventually publish a new mnemonic.
        viewModel.generateFinalMnemonic()
        val fresh = viewModel.finalMnemonic.first { it != null }
        assertNotNull(fresh)
        assertTrue(fresh.isNotEmpty())
    }
}

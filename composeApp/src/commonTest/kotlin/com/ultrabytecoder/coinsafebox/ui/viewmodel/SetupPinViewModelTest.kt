package com.ultrabytecoder.coinsafebox.ui.viewmodel

import com.ultrabytecoder.coinsafebox.data.ExistingKeyMaterialException
import com.ultrabytecoder.coinsafebox.data.SettingsStore
import com.ultrabytecoder.coinsafebox.domain.repository.ChangePinResult
import com.ultrabytecoder.coinsafebox.domain.repository.PinRepository
import com.ultrabytecoder.coinsafebox.domain.repository.PinState
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod
import com.ultrabytecoder.coinsafebox.domain.repository.VerifyResult
import com.ultrabytecoder.coinsafebox.domain.usecase.SetupPinUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F-2 regression: the recovery confirmation flow must be completable from the
 * numpad — a refused setup (ExistingKeyMaterialException) emits
 * RecoveryConfirmationRequired WITHOUT enabling recovery, and only an explicit
 * enterRecoveryMode() causes the re-entered PIN to be submitted with
 * recoveryAcknowledged = true.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupPinViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class InMemorySettingsStore : SettingsStore {
        private val map = HashMap<String, String>()
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getString(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
    }

    /**
     * Simulates existing key material when [existingKeyMaterial] is true: setup
     * is refused unless recoveryAcknowledged, mirroring PinRepositoryImpl's
     * hasRawEnvelope() guard. Records the last flag it was called with.
     */
    private class FakePinRepository(private val existingKeyMaterial: Boolean) : PinRepository {
        var setupCalls = 0
        var lastRecoveryAcknowledged: Boolean? = null

        override val pinStateFlow: StateFlow<PinState> =
            MutableStateFlow(PinState.Setup(failedAttempts = 0, lockedUntil = 0))
        override val securityMethodFlow: StateFlow<SecurityMethod?> =
            MutableStateFlow(SecurityMethod.PIN)

        override suspend fun setupPin(pin: CharArray, method: SecurityMethod, recoveryAcknowledged: Boolean) {
            setupCalls++
            lastRecoveryAcknowledged = recoveryAcknowledged
            if (existingKeyMaterial && !recoveryAcknowledged) {
                throw ExistingKeyMaterialException("Existing wallet key material found.")
            }
        }

        override suspend fun verifyPin(pin: CharArray): VerifyResult = VerifyResult.WrongPin(5)
        override suspend fun changePin(
            oldPin: CharArray,
            newPin: CharArray,
            newMethod: SecurityMethod
        ): ChangePinResult = ChangePinResult.Success
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModelFor(repo: FakePinRepository): SetupPinViewModel =
        SetupPinViewModel(SetupPinUseCase(repo), InMemorySettingsStore())

    @Test
    fun freshSetup_succeeds_withoutRecoveryAcknowledgement() = runTest(dispatcher) {
        val repo = FakePinRepository(existingKeyMaterial = false)
        val viewModel = viewModelFor(repo)
        val events = mutableListOf<SetupPinEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events.add(it) }
        }

        viewModel.selectPinLength(6)
        "123456".forEach { viewModel.addDigit(it) }
        "123456".forEach { viewModel.addDigit(it) }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repo.setupCalls)
        assertEquals(false, repo.lastRecoveryAcknowledged)
        assertEquals(listOf<SetupPinEvent>(SetupPinEvent.NavigateToCreateWallet), events)
        assertFalse(viewModel.state.value.isProcessing)
        collector.cancel()
    }

    @Test
    fun cancelRecoveryDialog_thenReenter_withoutAcknowledgement_staysRefused() = runTest(dispatcher) {
        val repo = FakePinRepository(existingKeyMaterial = true)
        val viewModel = viewModelFor(repo)
        val events = mutableListOf<SetupPinEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events.add(it) }
        }

        viewModel.selectPinLength(6)

        // Three separate attempts, each followed by dismissing the recovery
        // dialog WITHOUT acknowledging (no enterRecoveryMode): every attempt
        // must be refused, the state must reset cleanly for the next attempt,
        // and recoveryMode must never flip on its own.
        repeat(3) {
            "123456".forEach { viewModel.addDigit(it) }
            "123456".forEach { viewModel.addDigit(it) }
            dispatcher.scheduler.advanceUntilIdle()

            assertFalse(
                viewModel.state.value.recoveryMode,
                "recovery mode must never enable without explicit acknowledgement"
            )
            assertTrue(viewModel.state.value.errorMessage != null)
            assertFalse(viewModel.state.value.isConfirming, "state must reset for the next attempt")
            assertEquals(0, viewModel.state.value.enteredPinLength, "state must reset for the next attempt")
            assertFalse(viewModel.state.value.isProcessing)
        }

        assertEquals(3, repo.setupCalls)
        assertEquals(false, repo.lastRecoveryAcknowledged)
        assertEquals(
            3,
            events.count { it == SetupPinEvent.RecoveryConfirmationRequired },
            "each refused attempt must re-prompt the recovery dialog"
        )
        assertTrue(
            events.none { it == SetupPinEvent.NavigateToCreateWallet },
            "cancelled recovery must never navigate to wallet creation"
        )
        collector.cancel()
    }

    @Test
    fun recoveryRefusal_thenAcknowledgedRecovery_succeeds() = runTest(dispatcher) {
        val repo = FakePinRepository(existingKeyMaterial = true)
        val viewModel = viewModelFor(repo)
        val events = mutableListOf<SetupPinEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events.add(it) }
        }

        // First attempt: refused — key material exists, no acknowledgement.
        viewModel.selectPinLength(6)
        "123456".forEach { viewModel.addDigit(it) }
        "123456".forEach { viewModel.addDigit(it) }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repo.setupCalls)
        assertEquals(false, repo.lastRecoveryAcknowledged)
        assertEquals(listOf<SetupPinEvent>(SetupPinEvent.RecoveryConfirmationRequired), events)
        assertFalse(
            viewModel.state.value.recoveryMode,
            "recovery mode must not be enabled before the user acknowledges"
        )
        assertTrue(viewModel.state.value.errorMessage != null)

        // Re-entry WITHOUT acknowledgement: refused again, still no recovery mode.
        "123456".forEach { viewModel.addDigit(it) }
        "123456".forEach { viewModel.addDigit(it) }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, repo.setupCalls)
        assertEquals(
            listOf<SetupPinEvent>(
                SetupPinEvent.RecoveryConfirmationRequired,
                SetupPinEvent.RecoveryConfirmationRequired
            ),
            events
        )
        assertFalse(viewModel.state.value.recoveryMode)

        // User acknowledges recovery: the re-entered PIN is submitted with
        // recoveryAcknowledged = true and completes setup.
        viewModel.enterRecoveryMode()
        assertTrue(viewModel.state.value.recoveryMode)
        assertTrue(viewModel.state.value.errorMessage == null)

        "654321".forEach { viewModel.addDigit(it) }
        "654321".forEach { viewModel.addDigit(it) }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(3, repo.setupCalls)
        assertEquals(true, repo.lastRecoveryAcknowledged)
        assertEquals(
            listOf<SetupPinEvent>(
                SetupPinEvent.RecoveryConfirmationRequired,
                SetupPinEvent.RecoveryConfirmationRequired,
                SetupPinEvent.NavigateToCreateWallet
            ),
            events
        )
        assertFalse(
            viewModel.state.value.recoveryMode,
            "recovery mode must be cleared after a successful recovery setup"
        )
        assertFalse(viewModel.state.value.isProcessing)
        collector.cancel()
    }
}

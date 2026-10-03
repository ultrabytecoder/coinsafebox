package com.ultrabytecoder.coinsafebox.ui.viewmodel

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.data.walletconnect.AutoWcRelayTransport
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcController
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcCrypto
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcEncoding
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcPendingRequestHolder
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcProposalHolder
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcRelayClient
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcSessionManager
import com.ultrabytecoder.coinsafebox.data.walletconnect.buildTestWcRelay
import com.ultrabytecoder.coinsafebox.data.walletconnect.buildTestWcSessionManager
import com.ultrabytecoder.coinsafebox.data.walletconnect.dappPairingForTest
import com.ultrabytecoder.coinsafebox.data.walletconnect.newTestWcCrypto
import com.ultrabytecoder.coinsafebox.data.walletconnect.proposeEnvelopeForTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class WcPairViewModelTest {

    /** Harness: real manager/relay with an auto-answering fake transport. */
    private class Harness(
        val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        val projectId: String = "test-project-id",
    ) {
        val crypto: WcCrypto = newTestWcCrypto()
        val transport = AutoWcRelayTransport()
        val relay: WcRelayClient = buildTestWcRelay(crypto, transport, scheduler)
        val manager: WcSessionManager = buildTestWcSessionManager(crypto, relay, scheduler)
        val networkConfig = NetworkConfig.testnet("test-etherscan-key", projectId)
        val controller = WcController(crypto, manager, networkConfig, WcProposalHolder(), WcPendingRequestHolder())

        // Bypass controller.start() (it launches on Dispatchers.Default);
        // the VM only needs the manager running and the callbacks wired.
        suspend fun startManager() {
            manager.start()
        }
    }

    private suspend fun TestScope.withHarness(
        projectId: String = "test-project-id",
        block: suspend (h: Harness) -> Unit
    ) {
        val harness = Harness(testScheduler, projectId)
        harness.startManager()
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block(harness)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun pair_withValidUri_waitsForProposal() = runTest {
        withHarness { h ->
            val (pairingTopic, uri) = dappPairingForTest(h.crypto)
            assertTrue(pairingTopic.isNotBlank())

            val vm = WcPairViewModel(h.controller)
            vm.pair(uri)
            // No advanceUntilIdle here: with virtual time it would fast-forward the
            // 30s proposal wait straight to the timeout. The unconfined Main
            // dispatcher already ran pair() synchronously up to the wait.
            assertIs<WcPairViewModel.State.WaitingForProposal>(vm.state.value)
        }
    }

    @Test
    fun proposalArrivingDuringWait_transitionsToProposalReady() = runTest {
        withHarness { h ->
            val (pairingTopic, uri) = dappPairingForTest(h.crypto)
            val vm = WcPairViewModel(h.controller)
            vm.pair(uri)

            val proposerKeyPair = h.crypto.generateX25519KeyPair()
            val proposerPubHex = WcEncoding.hexEncode(proposerKeyPair.publicKey)
            h.transport.pushTopic(
                pairingTopic,
                proposeEnvelopeForTest(h.crypto, pairingTopic, proposerPubHex, 1001L),
                "p1"
            )
            testScheduler.advanceUntilIdle()

            assertIs<WcPairViewModel.State.ProposalReady>(vm.state.value)
            assertNotNull(h.controller.proposalHolder.pendingProposal.value)
            assertEquals(1001L, h.controller.proposalHolder.pendingProposal.value!!.id)
        }
    }

    @Test
    fun pair_withExpiredUri_fails() = runTest {
        withHarness { h ->
            val symKey = WcEncoding.hexEncode(h.crypto.generateRandomBytes32())
            val topic = h.crypto.hashKey(symKey)
            val past = (Clock.System.now().toEpochMilliseconds() / 1000 - 10).toInt()
            val uri = "wc:$topic@2?relay-protocol=irn&symKey=$symKey&expiryTimestamp=$past"

            val vm = WcPairViewModel(h.controller)
            vm.pair(uri)
            testScheduler.advanceUntilIdle()

            val state = vm.state.value
            assertIs<WcPairViewModel.State.Failed>(state)
            assertTrue(state.message.contains("expired", ignoreCase = true))
        }
    }

    @Test
    fun pair_withoutProjectId_failsAsUnavailable() = runTest {
        withHarness(projectId = "") { h ->
            val (pairingTopic, uri) = dappPairingForTest(h.crypto)
            assertTrue(pairingTopic.isNotBlank())

            val vm = WcPairViewModel(h.controller)
            vm.pair(uri)
            testScheduler.advanceUntilIdle()

            val state = vm.state.value
            assertIs<WcPairViewModel.State.Failed>(state)
            assertTrue(state.message.contains("not configured", ignoreCase = true))
        }
    }

    @Test
    fun pair_withBlankUri_fails() = runTest {
        withHarness { h ->
            val vm = WcPairViewModel(h.controller)
            vm.pair("   ")

            val state = vm.state.value
            assertIs<WcPairViewModel.State.Failed>(state)
            assertTrue(state.message.contains("connection link", ignoreCase = true))
        }
    }

    @Test
    fun pair_withStalePendingProposal_clearsItAndProceedsWithNewUri() = runTest {
        withHarness { h ->
            // A stale proposal from an earlier pairing is parked in the holder.
            val (topicA, uriA) = dappPairingForTest(h.crypto)
            h.manager.pair(uriA)
            val proposerKeyPair = h.crypto.generateX25519KeyPair()
            val pubHex = WcEncoding.hexEncode(proposerKeyPair.publicKey)
            h.transport.pushTopic(topicA, proposeEnvelopeForTest(h.crypto, topicA, pubHex, 500L), "p0")
            testScheduler.runCurrent()
            assertNotNull(h.controller.proposalHolder.pendingProposal.value)

            val vm = WcPairViewModel(h.controller)
            testScheduler.runCurrent()
            assertIs<WcPairViewModel.State.ProposalReady>(vm.state.value)

            // Starting a new pairing must clear the stale proposal instead of
            // immediately re-navigating to it.
            val (_, uriB) = dappPairingForTest(h.crypto)
            vm.pair(uriB)
            testScheduler.runCurrent()

            assertNull(h.controller.proposalHolder.pendingProposal.value)
            assertIs<WcPairViewModel.State.WaitingForProposal>(vm.state.value)
        }
    }

    @Test
    fun clearedProposalAfterReady_resetsToIdle() = runTest {
        withHarness { h ->
            val (topic, uri) = dappPairingForTest(h.crypto)
            h.manager.pair(uri)
            val proposerKeyPair = h.crypto.generateX25519KeyPair()
            val pubHex = WcEncoding.hexEncode(proposerKeyPair.publicKey)
            h.transport.pushTopic(topic, proposeEnvelopeForTest(h.crypto, topic, pubHex, 600L), "p1")
            testScheduler.runCurrent()

            val vm = WcPairViewModel(h.controller)
            testScheduler.runCurrent()
            assertIs<WcPairViewModel.State.ProposalReady>(vm.state.value)

            // The proposal is consumed/cleared elsewhere; the state must not
            // stay stuck on "Connection request received…".
            h.controller.proposalHolder.clear()
            testScheduler.runCurrent()

            assertIs<WcPairViewModel.State.Idle>(vm.state.value)
        }
    }

    @Test
    fun tryAgain_resetsToIdle() = runTest {
        withHarness { h ->
            val vm = WcPairViewModel(h.controller)
            vm.pair("   ")
            assertIs<WcPairViewModel.State.Failed>(vm.state.value)

            vm.tryAgain()
            assertIs<WcPairViewModel.State.Idle>(vm.state.value)
        }
    }
}

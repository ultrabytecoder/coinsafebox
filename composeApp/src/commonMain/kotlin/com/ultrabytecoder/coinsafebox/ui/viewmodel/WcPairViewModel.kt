package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcController
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcProtocolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Drives the "connect to dApp" flow: pair from a scanned/pasted `wc:` URI,
 * wait for the dApp's session proposal, and flip to [State.ProposalReady]
 * when it arrives (the screen then navigates to the approval screen).
 */
class WcPairViewModel(
    val wcController: WcController,
) : ViewModel() {

    sealed class State {
        data object Idle : State()
        data object Pairing : State()
        data object WaitingForProposal : State()
        data object ProposalReady : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    init {
        // A proposal may arrive at any time (even after a previous wait timed
        // out); park it as ready so the screen navigates to the approval UI.
        // A clear (null) returns the state to Idle so a disposed proposal never
        // leaves the screen stuck on "Connection request received…".
        viewModelScope.launch {
            wcController.proposalHolder.pendingProposal.collect { proposal ->
                if (proposal != null) {
                    _state.value = State.ProposalReady
                } else if (_state.value is State.ProposalReady) {
                    _state.value = State.Idle
                }
            }
        }
    }

    fun pair(uri: String) {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) {
            _state.value = State.Failed("Enter a WalletConnect connection link first.")
            return
        }
        if (!wcController.isAvailable()) {
            _state.value = State.Failed("WalletConnect is not configured in this build (missing relay project ID).")
            return
        }
        if (_state.value is State.Pairing || _state.value is State.WaitingForProposal) return

        // Set synchronously before launching so a double-tap cannot pass the
        // guard twice and create two concurrent pairings.
        _state.value = State.Pairing
        viewModelScope.launch {
            // A stale proposal from a previous pairing (e.g. the user backed out
            // of the approval screen) must not immediately re-trigger the
            // ProposalReady navigation for the new URI.
            wcController.proposalHolder.clear()
            try {
                wcController.sessionManager.pair(trimmed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WcProtocolException) {
                _state.value = State.Failed(e.message ?: "Pairing failed.")
                return@launch
            } catch (e: Exception) {
                _state.value = State.Failed("Could not start pairing: ${e.message ?: e::class.simpleName}")
                return@launch
            }

            _state.value = State.WaitingForProposal
            try {
                withTimeout(WAIT_FOR_PROPOSAL_MILLIS) {
                    wcController.proposalHolder.pendingProposal.filterNotNull().first()
                }
                // Proposal arrived; the collector above already set ProposalReady.
            } catch (e: TimeoutCancellationException) {
                if (_state.value is State.WaitingForProposal) {
                    _state.value = State.Failed(
                        "No connection request received yet. You can go back — if the dApp " +
                            "sends one later, it will show up on this screen."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    fun tryAgain() {
        _state.value = State.Idle
    }

    companion object {
        private const val WAIT_FOR_PROPOSAL_MILLIS = 30_000L
    }
}

package com.ultrabytecoder.coinsafebox.data.walletconnect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-level holder for the latest pending session proposal. The session
 * manager fires `onProposal` from a background dispatcher, so the proposal is
 * parked here in a StateFlow that survives screen disposal: the pair screen
 * observes it while active, and the accounts list shows a badge if the user
 * navigates away before a proposal arrives. Cleared once the proposal is
 * consumed (approved or rejected) or replaced by a newer one.
 */
class WcProposalHolder {
    private val _pendingProposal = MutableStateFlow<WcProposal?>(null)
    val pendingProposal: StateFlow<WcProposal?> = _pendingProposal

    fun set(proposal: WcProposal?) {
        _pendingProposal.value = proposal
    }

    fun clear() {
        _pendingProposal.value = null
    }
}

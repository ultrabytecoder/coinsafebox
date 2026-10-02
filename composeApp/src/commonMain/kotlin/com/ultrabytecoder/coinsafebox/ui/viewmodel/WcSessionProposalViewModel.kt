package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcController
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcProposal
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcProtocolException
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcSessionManager
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountsUseCase
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Approval screen for a dApp session proposal: shows the dApp's metadata and
 * the requested chains, lets the user pick which ETH accounts to expose
 * (CAIP-10 `eip155:<chainId>:<address>`), and approves/rejects.
 */
class WcSessionProposalViewModel(
    private val proposalId: Long,
    private val walletId: Long,
    private val wcController: WcController,
    private val getAccounts: GetAccountsUseCase,
    private val networkConfig: NetworkConfig,
) : ViewModel() {

    data class State(
        val isLoading: Boolean = true,
        val proposal: WcProposal? = null,
        val accounts: List<AccountInfo> = emptyList(),
        val selectedAddresses: Set<String> = emptySet(),
        val chainMismatch: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val done: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    val chainId: Long = networkConfig.ethChainId

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val proposal = wcController.sessionManager.getProposal(proposalId)
            if (proposal == null) {
                wcController.proposalHolder.clear()
                _state.value = State(isLoading = false, error = "Proposal not found or expired.")
                return@launch
            }
            val nowMillis = Clock.System.now().toEpochMilliseconds()
            if (proposal.expiry * 1000L <= nowMillis) {
                wcController.proposalHolder.clear()
                _state.value = State(isLoading = false, error = "This connection request has expired.")
                return@launch
            }
            val accounts = getAccounts.byWallet(walletId).first()
                .filter { it.type == AccountType.Eth && !it.address.isNullOrBlank() }
            val requestedChains = (
                proposal.requiredNamespaces[WcSessionManager.EIP155]?.chains.orEmpty() +
                    proposal.optionalNamespaces[WcSessionManager.EIP155]?.chains.orEmpty()
                ).distinct()
            // The app's chain is fixed at build time; if the dApp only asks for
            // other chains, no account of ours can satisfy the proposal.
            val chainMismatch = requestedChains.isNotEmpty() &&
                requestedChains.none { it == "$EIP155:$chainId" }
            _state.value = State(
                isLoading = false,
                proposal = proposal,
                accounts = accounts,
                selectedAddresses = accounts.firstOrNull()?.address?.let { setOf(it) } ?: emptySet(),
                chainMismatch = chainMismatch,
            )
        }
    }

    fun toggleAccount(address: String) {
        val s = _state.value
        if (s.busy || s.done) return
        val updated = if (address in s.selectedAddresses) {
            s.selectedAddresses - address
        } else {
            s.selectedAddresses + address
        }
        _state.value = s.copy(selectedAddresses = updated, error = null)
    }

    fun approve() {
        val s = _state.value
        if (s.busy || s.done || s.isLoading || s.chainMismatch || s.selectedAddresses.isEmpty()) return
        // Set synchronously so a double-tap cannot pass the guard twice.
        // Since toggleAccount() is now guarded by busy, s.selectedAddresses
        // is guaranteed to be the final selection.
        _state.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val caip10 = s.selectedAddresses.map { "eip155:$chainId:$it" }
                wcController.sessionManager.approve(proposalId, caip10)
                _state.value = _state.value.copy(busy = false, done = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = e.message ?: "Failed to approve.")
            } finally {
                wcController.proposalHolder.clear()
                wcController.refresh()
            }
        }
    }

    fun reject() {
        val s = _state.value
        if (s.busy || s.done || s.isLoading) return
        // Set synchronously so a double-tap cannot pass the guard twice.
        _state.value = s.copy(busy = true)
        viewModelScope.launch {
            try {
                wcController.sessionManager.reject(proposalId)
                // Success: the dApp was told; navigate away.
                wcController.proposalHolder.clear()
                _state.value = _state.value.copy(busy = false, done = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: WcProtocolException) {
                // Proposal already gone (expired/deleted) — safe to dismiss.
                wcController.proposalHolder.clear()
                _state.value = _state.value.copy(busy = false, done = true)
            } catch (e: Exception) {
                // Network error — keep the user on screen so they can retry.
                _state.value = _state.value.copy(busy = false, error = e.message ?: "Could not reject the request.")
            }
        }
    }

    companion object {
        private const val EIP155 = "eip155"
    }
}

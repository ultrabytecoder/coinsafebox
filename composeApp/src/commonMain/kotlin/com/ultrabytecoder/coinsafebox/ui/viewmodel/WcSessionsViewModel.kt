package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcController
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcSession
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Lists active WalletConnect sessions and disconnects them on demand.
 */
class WcSessionsViewModel(
    private val wcController: WcController,
) : ViewModel() {

    val sessions: StateFlow<List<WcSession>> = wcController.sessions

    fun disconnect(topic: String) {
        viewModelScope.launch {
            try {
                wcController.sessionManager.disconnectSession(topic)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The relay may briefly be down (e.g. right after an unlock
                // reconnection); the auto-reconnect recovers, and the user can
                // retry from the list. The session row stays until then.
                println("WcSessionsViewModel: disconnect failed for $topic — ${e.message}")
            }
        }
    }
}

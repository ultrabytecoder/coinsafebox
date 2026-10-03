package com.ultrabytecoder.coinsafebox.data.walletconnect

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * App-lifecycle wrapper around [WcSessionManager]. Owns the relay connection
 * for the duration of an unlocked session: [start] is called once after the
 * DB unlock, [stop] when the session locks (app backgrounded / idle). Keeps
 * the session list as a [StateFlow] for the UI and parks incoming
 * proposals in [WcProposalHolder] so they survive screen disposal.
 */
class WcController internal constructor(
    private val crypto: WcCrypto,
    internal val sessionManager: WcSessionManager,
    private val networkConfig: NetworkConfig,
    val proposalHolder: WcProposalHolder,
    val pendingRequestHolder: WcPendingRequestHolder,
) {
    private val _sessions = MutableStateFlow<List<WcSession>>(emptyList())
    val sessions: StateFlow<List<WcSession>> = _sessions

    // App-lifetime scope (not tied to any ViewModel) so an in-flight relay
    // start survives the EnterPin screen being disposed right after unlock.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    @Volatile
    private var startJob: Job? = null

    init {
        sessionManager.onProposal = { proposal -> proposalHolder.set(proposal) }
        sessionManager.onSessionApproved = { refresh() }
        sessionManager.onSessionDeleted = { _, _ -> refresh() }
    }

    /** WalletConnect is usable only when a relay project ID was configured at build time. */
    fun isAvailable(): Boolean = networkConfig.wcProjectId.isNotBlank()

    /** Opens the relay and loads persisted sessions. Idempotent. */
    fun start() {
        if (!isAvailable() || started) return
        started = true
        startJob = scope.launch {
            try {
                crypto.init()
                if (!started) return@launch
                sessionManager.start()
                if (!started) {
                    sessionManager.stop()
                    return@launch
                }
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Reset so a subsequent start() attempt can retry after a
                // transient failure (e.g. network blip on first connect).
                started = false
                // sessionManager.start() may have set its internal flag before
                // relay.connect() threw; stop() resets it so the next start()
                // doesn't see a stale "already started" state.
                sessionManager.stop()
            }
        }
    }

    /** Closes the relay and drops in-memory session state. Call on session lock. */
    fun stop() {
        if (!started) return
        started = false
        // Cancel any in-flight start() coroutine so it cannot reconnect the
        // relay after we've locked (e.g. crypto.init() still in progress).
        startJob?.cancel()
        startJob = null
        // A pending proposal almost certainly expires (5-minute TTL) while the
        // app is locked; don't resurface a stale banner after unlock.
        proposalHolder.clear()
        // A pending request's deferred will never be completed while locked;
        // cancel it so the handler coroutine doesn't leak.
        pendingRequestHolder.pendingRequest.value?.deferred?.cancel()
        pendingRequestHolder.clear()
        sessionManager.stop()
        _sessions.value = emptyList()
    }

    fun refresh() {
        if (!started) return
        _sessions.value = sessionManager.getSessions()
    }
}

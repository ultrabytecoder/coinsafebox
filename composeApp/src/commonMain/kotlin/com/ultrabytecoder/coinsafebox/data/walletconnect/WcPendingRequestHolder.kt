package com.ultrabytecoder.coinsafebox.data.walletconnect

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

/**
 * A decrypted [WcSessionRequest] parked for user approval. The [deferred] is
 * completed by the ViewModel when the user approves (with the signed tx) or
 * rejects (with an error), which unblocks the suspended [WcRequestHandler].
 */
data class WcPendingRequest(
    val sessionTopic: String,
    val requestId: Long,
    val chainId: String,
    val requestMethod: String,
    val requestParams: JsonElement,
    val dAppName: String,
    val deferred: CompletableDeferred<WcRequestOutcome>,
)

/**
 * Process-level holder for the latest pending session request. The request
 * handler parks it here and suspends on [WcPendingRequest.deferred]; the
 * confirmation screen observes this flow to show the approval UI.
 */
class WcPendingRequestHolder {
    private val _pendingRequest = MutableStateFlow<WcPendingRequest?>(null)
    val pendingRequest: StateFlow<WcPendingRequest?> = _pendingRequest

    fun set(request: WcPendingRequest?) {
        _pendingRequest.value = request
    }

    fun clear() {
        _pendingRequest.value = null
    }
}

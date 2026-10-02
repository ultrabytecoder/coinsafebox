package com.ultrabytecoder.coinsafebox.data.walletconnect

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges incoming [WcSessionRequest]s to the UI: parks the request in
 * [WcPendingRequestHolder] and suspends until the user approves or rejects
 * on the confirmation screen.
 */
class WcRequestHandler(
    private val pendingRequestHolder: WcPendingRequestHolder,
    private val networkConfig: NetworkConfig,
) : WcSessionRequestHandler {

    override suspend fun handleRequest(session: WcSession, request: WcSessionRequest): WcRequestOutcome {
        // Only support eth_sendTransaction for now; other methods get a
        // "not supported" response so the dApp can fall back or show an error.
        if (request.requestMethod != "eth_sendTransaction") {
            return WcRequestOutcome.Failure(
                WcSessionManager.ERROR_METHOD_NOT_APPROVED,
                "Method ${request.requestMethod} is not supported yet"
            )
        }

        // Reject requests for a different chain than the wallet is configured for.
        val requestChainId = request.chainId?.removePrefix("eip155:")
        val walletChainId = networkConfig.ethChainId.toString()
        if (requestChainId != null && requestChainId != walletChainId) {
            return WcRequestOutcome.Failure(
                WcSessionManager.ERROR_METHOD_NOT_APPROVED,
                "Chain $requestChainId not supported by this wallet (configured for $walletChainId)"
            )
        }

        // If a previous request is still pending (e.g. user hasn't responded
        // yet), complete it with a failure so the handler coroutine doesn't leak.
        pendingRequestHolder.pendingRequest.value?.let { previous ->
            previous.deferred.complete(
                WcRequestOutcome.Failure(5001, "Superseded by a new request")
            )
        }

        val deferred = CompletableDeferred<WcRequestOutcome>()
        pendingRequestHolder.set(
            WcPendingRequest(
                sessionTopic = session.topic,
                requestId = request.id,
                chainId = request.chainId ?: "",
                requestMethod = request.requestMethod,
                requestParams = request.requestParams,
                dAppName = session.proposerMetadata.name.ifBlank { "dApp" },
                deferred = deferred,
            )
        )

        return try {
            deferred.await()
        } catch (e: CancellationException) {
            WcRequestOutcome.Failure(5001, "Request cancelled")
        } finally {
            // Only clear if this is still the current request (a newer one
            // may have already replaced it).
            if (pendingRequestHolder.pendingRequest.value?.deferred === deferred) {
                pendingRequestHolder.clear()
            }
        }
    }
}

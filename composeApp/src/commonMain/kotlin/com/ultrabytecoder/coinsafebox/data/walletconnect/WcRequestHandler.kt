package com.ultrabytecoder.coinsafebox.data.walletconnect

import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges incoming [WcSessionRequest]s to the UI: parks the request in
 * [WcPendingRequestHolder] and suspends until the user approves or rejects
 * on the confirmation screen.
 */
class WcRequestHandler(
    private val pendingRequestHolder: WcPendingRequestHolder,
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

        val outcome = deferred.await()
        pendingRequestHolder.clear()
        return outcome
    }
}

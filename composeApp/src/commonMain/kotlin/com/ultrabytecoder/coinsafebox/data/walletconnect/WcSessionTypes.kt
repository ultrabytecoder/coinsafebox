package com.ultrabytecoder.coinsafebox.data.walletconnect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class WcMetadata(
    val name: String,
    val description: String,
    val url: String,
    val icons: List<String> = emptyList(),
)

/**
 * A namespace entry. In a proposal, [chains] is populated (accounts is null).
 * In an approved/settled session, [accounts] is populated.
 */
@Serializable
data class WcNamespace(
    val chains: List<String>? = null,
    val accounts: List<String>? = null,
    val methods: List<String> = emptyList(),
    val events: List<String> = emptyList(),
)

@Serializable
data class WcProposal(
    val id: Long,
    val proposerPublicKey: String,
    val proposerMetadata: WcMetadata,
    val requiredNamespaces: Map<String, WcNamespace>,
    val optionalNamespaces: Map<String, WcNamespace>,
    val pairingTopic: String,
    val expiry: Long,
)

@Serializable
data class WcSession(
    val topic: String,
    val pairingTopic: String,
    val proposerPublicKey: String,
    val proposerMetadata: WcMetadata,
    val responderPublicKey: String,
    val responderMetadata: WcMetadata,
    val namespaces: Map<String, WcNamespace>,
    val expiry: Long,
    val acknowledged: Boolean = false,
    val settleRequestId: Long? = null,
)

@Serializable
data class WcPairing(
    val topic: String,
    val expiry: Long,
    val relayProtocol: String = "irn",
    val active: Boolean = false,
    val methods: List<String>? = null,
)

data class WcRpcError(val code: Int, val message: String)

/** A decrypted `wc_sessionRequest` from the dApp. */
data class WcSessionRequest(
    val id: Long,
    val chainId: String?,
    val requestMethod: String,
    val requestParams: JsonElement,
)

/** Result of handling an incoming [WcSessionRequest]. */
sealed class WcRequestOutcome {
    data class Success(val result: JsonElement) : WcRequestOutcome()
    data class Failure(val code: Int, val message: String) : WcRequestOutcome()
}

/**
 * Implemented by the wallet's EVM (or other chain) logic to fulfil incoming
 * `wc_sessionRequest`s (e.g. personal_sign, eth_sendTransaction). Wired in at
 * construction; when null, incoming requests are answered with "not supported".
 */
interface WcSessionRequestHandler {
    suspend fun handleRequest(session: WcSession, request: WcSessionRequest): WcRequestOutcome
}

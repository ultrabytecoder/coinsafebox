package com.ultrabytecoder.coinsafebox.data.walletconnect

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.time.Clock

/**
 * High-level WalletConnect v2 session manager: pairing, session proposal handling,
 * approve/reject/settle, and session lifecycle (ping/delete/update/extend). It owns
 * the in-memory session/pairing state, persists sessions via [storage], and routes
 * decrypted topic messages to the appropriate handler.
 *
 * EVM fulfilment of incoming `wc_sessionRequest`s is delegated to [requestHandler].
 */
internal class WcSessionManager(
    private val crypto: WcCrypto,
    private val relay: WcRelayClient,
    private val walletMetadata: WcMetadata,
    private val supportedChainIds: List<Long> = listOf(1L),
    private val requestHandler: WcSessionRequestHandler? = null,
    private val sessionRepository: WcSessionRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    @Volatile
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val json = Json { ignoreUnknownKeys = true }

    private val stateLock = Any()
    private val pairings = mutableMapOf<String, WcPairing>()
    private val proposals = mutableMapOf<Long, WcProposal>()
    private val sessions = mutableMapOf<String, WcSession>()

    @Volatile
    private var started = false

    var onProposal: ((WcProposal) -> Unit)? = null
    var onSessionApproved: ((WcSession) -> Unit)? = null
    var onSessionDeleted: ((topic: String, reason: WcRpcError?) -> Unit)? = null

    // ---------- Lifecycle ----------------------------------------------------- //

    /** Must be called before use; loads persisted sessions and opens the relay. */
    suspend fun start() {
        if (started) return
        started = true
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + dispatcher)
        loadSessions()
        relay.setOnMessage { topic, payloadJson -> onMessage(topic, payloadJson) }
        relay.connect()
    }

    fun stop() {
        started = false
        relay.setOnMessage(null)
        relay.disconnect()
        scope.cancel()
    }

    // ---------- Pairing ------------------------------------------------------- //

    suspend fun pair(uri: String): WcPairing {
        val parsed = WcUri.parseUri(uri)
        val nowMillis = Clock.System.now().toEpochMilliseconds()
        parsed.expiryTimestamp?.let { exp ->
            if (exp * 1000L < nowMillis) throw WcProtocolException("pairing URI has expired")
        }
        if (!crypto.hasKey(parsed.topic)) {
            crypto.setSymKey(WcEncoding.hexDecode(parsed.symKey), parsed.topic)
        }
        val expiry = parsed.expiryTimestamp ?: (nowMillis / 1000L + FIVE_MINUTES)
        val pairing = WcPairing(
            topic = parsed.topic,
            expiry = expiry,
            relayProtocol = parsed.relayProtocol,
            active = false,
            methods = parsed.methods,
        )
        synchronized(stateLock) { pairings[parsed.topic] = pairing }
        ensureConnected()
        relay.subscribe(parsed.topic)
        return pairing
    }

    fun getPairing(topic: String): WcPairing? = synchronized(stateLock) { pairings[topic] }

    // ---------- Proposal ------------------------------------------------------ //

    fun getProposal(id: Long): WcProposal? = synchronized(stateLock) { proposals[id] }

    /**
     * Approves [proposalId] for the given CAIP-10 [selectedAccounts]
     * (e.g. `eip155:1:0xabc`). Derives the session topic, subscribes, and sends
     * `wc_approveSession`. Returns the (not yet acknowledged) session.
     */
    suspend fun approve(proposalId: Long, selectedAccounts: List<String>): WcSession {
        val proposal = synchronized(stateLock) { proposals[proposalId] }
            ?: throw WcProtocolException("unknown proposal: $proposalId")
        if (isExpired(proposal.expiry)) {
            synchronized(stateLock) { proposals.remove(proposalId) }
            throw WcProtocolException("proposal $proposalId has expired")
        }
        val pairingTopic = proposal.pairingTopic

        val approved = computeApprovedNamespaces(proposal, selectedAccounts)
        val settleId = WcJsonRpc.payloadId()
        val expiry = calcExpiry(SEVEN_DAYS)

        // Key material and the subscription are created INSIDE the try so that any failure
        // (crypto or network) cleans up whatever was already written — no orphaned X25519
        // private key or symKey.
        var walletPublicKey: String? = null
        var sessionTopic: String? = null
        try {
            val keyPair = crypto.generateX25519KeyPair()
            walletPublicKey = WcEncoding.hexEncode(keyPair.publicKey)
            sessionTopic = crypto.sessionTopicFor(walletPublicKey!!, proposal.proposerPublicKey)
            val pub = walletPublicKey!!
            val topic = sessionTopic!!

            ensureConnected()
            relay.subscribe(topic)

            val proposalResult = WcJsonRpc.formatJsonRpcResult(
                proposalId,
                buildJsonObject {
                    put("relay", buildJsonObject { put("protocol", RELAY_PROTOCOL) })
                    put("responderPublicKey", pub)
                },
            )
            val proposalEnvelope = crypto.encodeEnvelope(pairingTopic, proposalResult.toString())

            val settleParams = buildJsonObject {
                put("relay", buildJsonObject { put("protocol", RELAY_PROTOCOL) })
                put("namespaces", approved.toJsonObject())
                put(
                    "controller",
                    buildJsonObject {
                        put("publicKey", pub)
                        put("metadata", walletMetadata.toJsonObject())
                    },
                )
                put("expiry", expiry)
            }
            val settleRequest = WcJsonRpc.formatJsonRpcRequest("wc_sessionSettle", settleParams, settleId)
            val settleEnvelope = crypto.encodeEnvelope(topic, settleRequest.toString())

            relay.relayRequest(
                "wc_approveSession",
                buildJsonObject {
                    put("sessionTopic", topic)
                    put("pairingTopic", pairingTopic)
                    put("sessionProposalResponse", proposalEnvelope)
                    put("sessionSettlementRequest", settleEnvelope)
                    put("ttl", APPROVE_SESSION_TTL)
                },
            )
        } catch (e: Exception) {
            // Remove any key material / subscription created before the failure.
            walletPublicKey?.let { crypto.deleteSymKey(it) }
            sessionTopic?.let { topic ->
                crypto.deleteSymKey(topic)
                runCatching { relay.release(topic) }
                    .onFailure { println("WcSessionManager: cleanup release failed on approve error — ${it.message}") }
            }
            throw e
        }

        val session = WcSession(
            topic = sessionTopic!!,
            pairingTopic = pairingTopic,
            proposerPublicKey = proposal.proposerPublicKey,
            proposerMetadata = proposal.proposerMetadata,
            responderPublicKey = walletPublicKey!!,
            responderMetadata = walletMetadata,
            namespaces = approved,
            expiry = expiry,
            acknowledged = false,
            settleRequestId = settleId,
        )
        synchronized(stateLock) {
            sessions[sessionTopic!!] = session
            proposals.remove(proposalId)
        }
        saveSessions()
        return session
    }

    suspend fun reject(proposalId: Long, reason: WcRpcError = WcRpcError(ERROR_USER_REJECTED, "User rejected")) {
        val proposal = synchronized(stateLock) { proposals[proposalId] }
            ?: throw WcProtocolException("unknown proposal: $proposalId")
        if (isExpired(proposal.expiry)) {
            synchronized(stateLock) { proposals.remove(proposalId) }
            throw WcProtocolException("proposal $proposalId has expired")
        }
        val payload = WcJsonRpc.formatJsonRpcError(proposalId, reason.code, reason.message)
        val envelope = crypto.encodeEnvelope(proposal.pairingTopic, payload.toString())
        ensureConnected()
        relay.publish(
            proposal.pairingTopic,
            envelope,
            ttl = PROPOSE_REJECT_TTL,
            prompt = false,
            tag = PROPOSE_REJECT_TAG,
        )
        synchronized(stateLock) { proposals.remove(proposalId) }
    }

    // ---------- Sessions ------------------------------------------------------ //

    fun getSessions(): List<WcSession> = synchronized(stateLock) { sessions.values.toList() }

    fun getSession(topic: String): WcSession? = synchronized(stateLock) { sessions[topic] }

    /** Wallet-initiated session disconnect: notify the dApp, then delete locally. */
    suspend fun disconnectSession(topic: String, reason: WcRpcError = WcRpcError(ERROR_USER_DISCONNECTED, "User disconnected")) {
        val session = synchronized(stateLock) { sessions[topic] }
            ?: throw WcProtocolException("unknown session: $topic")
        val payload = WcJsonRpc.formatJsonRpcRequest(
            "wc_sessionDelete",
            buildJsonObject {
                put("code", reason.code)
                put("message", reason.message)
            },
            WcJsonRpc.payloadId(),
        )
        val envelope = crypto.encodeEnvelope(topic, payload.toString())
        ensureConnected()
        relay.publish(topic, envelope, ttl = SESSION_DELETE_REQ_TTL, prompt = false, tag = SESSION_DELETE_REQ_TAG)
        deleteSessionLocal(topic)
        onSessionDeleted?.invoke(topic, reason)
    }

    suspend fun ping(topic: String) {
        val payload = WcJsonRpc.formatJsonRpcRequest("wc_sessionPing", buildJsonObject {}, WcJsonRpc.payloadId())
        val envelope = crypto.encodeEnvelope(topic, payload.toString())
        ensureConnected()
        relay.publish(topic, envelope, ttl = SESSION_PING_REQ_TTL, prompt = false, tag = SESSION_PING_REQ_TAG)
    }

    // ---------- Message routing ---------------------------------------------- //

    private fun onMessage(topic: String, payloadJson: String) {
        val payload = try {
            json.parseToJsonElement(payloadJson).jsonObject
        } catch (e: Exception) {
            println("WcSessionManager: ignoring non-JSON payload — ${e.message}")
            return
        }
        scope.launch {
            try {
                when {
                    WcJsonRpc.isJsonRpcRequest(payload) -> onIncomingRequest(topic, payload)
                    WcJsonRpc.isJsonRpcResponse(payload) -> onIncomingResponse(topic, payload)
                }
            } catch (e: Exception) {
                println("WcSessionManager: error handling message on $topic — ${e.message}")
            }
        }
    }

    private suspend fun onIncomingRequest(topic: String, payload: JsonObject) {
        val method = payload["method"]?.jsonPrimitive?.contentOrNull ?: return
        val id = payload["id"]?.jsonPrimitive?.long ?: return
        when (method) {
            "wc_sessionPropose" -> handlePropose(topic, payload)
            "wc_sessionRequest" -> handleSessionRequest(topic, payload)
            "wc_sessionPing" -> {
                val session = liveSession(topic, id, SESSION_PING_RES_TTL, SESSION_PING_RES_TAG)
                if (session != null) {
                    sendResult(topic, id, JsonPrimitive(true), SESSION_PING_RES_TTL, SESSION_PING_RES_TAG)
                }
            }
            "wc_sessionDelete" -> handleSessionDeleteRequest(topic, payload)
            "wc_sessionUpdate" -> handleSessionUpdate(topic, payload)
            "wc_sessionExtend" -> handleSessionExtend(topic, payload)
            else -> println("WcSessionManager: unhandled method $method on $topic")
        }
    }

    private suspend fun onIncomingResponse(topic: String, payload: JsonObject) {
        val id = payload["id"]?.jsonPrimitive?.long ?: return
        val session = synchronized(stateLock) { sessions[topic] } ?: return
        if (session.settleRequestId != id) return
        if (WcJsonRpc.isJsonRpcResult(payload)) {
            val settled = session.copy(acknowledged = true, settleRequestId = null)
            synchronized(stateLock) { sessions[topic] = settled }
            saveSessions()
            onSessionApproved?.invoke(settled)
        } else {
            val reason = payload["error"]?.jsonObject?.let { parseError(it) }
            deleteSessionLocal(topic)
            onSessionDeleted?.invoke(topic, reason)
        }
    }

    private suspend fun handlePropose(pairingTopic: String, payload: JsonObject) {
        val params = payload["params"]?.jsonObject ?: return
        val proposalId = payload["id"]?.jsonPrimitive?.long ?: return
        val proposer = params["proposer"]?.jsonObject ?: return
        val proposerPublicKey = proposer["publicKey"]?.jsonPrimitive?.contentOrNull ?: return
        val proposal = WcProposal(
            id = proposalId,
            proposerPublicKey = proposerPublicKey,
            proposerMetadata = parseMetadata(proposer["metadata"]?.jsonObject),
            requiredNamespaces = parseNamespaces(params["requiredNamespaces"]?.jsonObject),
            optionalNamespaces = parseNamespaces(params["optionalNamespaces"]?.jsonObject),
            pairingTopic = pairingTopic,
            expiry = params["expiry"]?.jsonPrimitive?.long
                ?: (Clock.System.now().toEpochMilliseconds() / 1000L + FIVE_MINUTES),
        )
        synchronized(stateLock) { proposals[proposalId] = proposal }
        onProposal?.invoke(proposal)
    }

    private suspend fun handleSessionRequest(sessionTopic: String, payload: JsonObject) {
        val params = payload["params"]?.jsonObject
        val id = payload["id"]?.jsonPrimitive?.long
        // A malformed frame with no id can't be responded to; drop it.
        if (params == null || id == null) return
        val inner = params["request"]?.jsonObject
        val requestMethod = inner?.get("method")?.jsonPrimitive?.contentOrNull
        // We have the id, so report a parse error rather than leaving the dApp to time out at 900s.
        if (inner == null || requestMethod == null) {
            sendError(
                sessionTopic, id, ERROR_PARSE, "Malformed wc_sessionRequest",
                SESSION_REQUEST_RES_TTL, SESSION_REQUEST_RES_TAG,
            )
            return
        }
        val requestParams: JsonElement = inner["params"] ?: JsonPrimitive("")
        val chainId = params["chainId"]?.jsonPrimitive?.contentOrNull
        val session = liveSession(sessionTopic, id, SESSION_REQUEST_RES_TTL, SESSION_REQUEST_RES_TAG) ?: return

        // Enforce the approved-namespace boundary before touching the request handler: never
        // honour a method/chain the user did not approve for this session.
        if (!isApprovedRequest(session, chainId, requestMethod)) {
            sendError(
                sessionTopic, id, ERROR_METHOD_NOT_APPROVED,
                "Method $requestMethod not approved for this session",
                SESSION_REQUEST_RES_TTL, SESSION_REQUEST_RES_TAG,
            )
            return
        }

        val request = WcSessionRequest(id, chainId, requestMethod, requestParams)

        val outcome = try {
            requestHandler?.handleRequest(session, request)
                ?: WcRequestOutcome.Failure(ERROR_NOT_SUPPORTED, "no request handler")
        } catch (e: Exception) {
            println("WcSessionManager: request handler threw on $sessionTopic — ${e.message}")
            WcRequestOutcome.Failure(ERROR_INTERNAL, "Internal error")
        }

        val response = when (outcome) {
            is WcRequestOutcome.Success -> WcJsonRpc.formatJsonRpcResult(id, outcome.result)
            is WcRequestOutcome.Failure -> WcJsonRpc.formatJsonRpcError(id, outcome.code, outcome.message)
        }
        publishOnTopic(sessionTopic, response, SESSION_REQUEST_RES_TTL, SESSION_REQUEST_RES_TAG)
    }

    private suspend fun handleSessionDeleteRequest(sessionTopic: String, payload: JsonObject) {
        val id = payload["id"]?.jsonPrimitive?.long ?: return
        val reason = payload["params"]?.jsonObject?.let { parseError(it) }
        // Ack best-effort (needs the symKey); delete locally regardless so a failed response
        // cannot leave a ghost session that the dApp has already dropped.
        runCatching { sendResult(sessionTopic, id, JsonPrimitive(true), SESSION_DELETE_RES_TTL, SESSION_DELETE_RES_TAG) }
            .onFailure { println("WcSessionManager: failed to ack session delete on $sessionTopic — ${it.message}") }
        deleteSessionLocal(sessionTopic)
        onSessionDeleted?.invoke(sessionTopic, reason)
    }

    private suspend fun handleSessionUpdate(sessionTopic: String, payload: JsonObject) {
        val id = payload["id"]?.jsonPrimitive?.long ?: return
        // liveSession silently drops unknown sessions and sends error 6 + deletes expired ones.
        // For a live session the wallet is always the controller, so a dApp-initiated update is
        // unauthorized (error 3003) and does not mutate the session.
        val session = liveSession(sessionTopic, id, SESSION_UPDATE_RES_TTL, SESSION_UPDATE_RES_TAG) ?: return
        sendError(
            sessionTopic,
            id,
            ERROR_UNAUTHORIZED_UPDATE,
            "Unauthorized update request",
            SESSION_UPDATE_RES_TTL,
            SESSION_UPDATE_RES_TAG,
        )
    }

    private suspend fun handleSessionExtend(sessionTopic: String, payload: JsonObject) {
        val id = payload["id"]?.jsonPrimitive?.long ?: return
        val params = payload["params"]?.jsonObject
        val session = liveSession(sessionTopic, id, SESSION_EXTEND_RES_TTL, SESSION_EXTEND_RES_TAG) ?: return
        val now = Clock.System.now().toEpochMilliseconds() / 1000L
        val maxExpiry = calcExpiry(SEVEN_DAYS)
        val proposed = params?.get("expiry")?.jsonPrimitive?.long
        // Extend must only ever push the expiry later: clamp the proposal to [now+1, 7d] and never
        // shrink below the current expiry, so a bogus/past value can't kill the session.
        val clamped = (proposed ?: maxExpiry).coerceIn(now + 1, maxExpiry)
        val expiry = maxOf(session.expiry, clamped)
        val updated = session.copy(expiry = expiry)
        synchronized(stateLock) { sessions[sessionTopic] = updated }
        saveSessions()
        sendResult(sessionTopic, id, JsonPrimitive(true), SESSION_EXTEND_RES_TTL, SESSION_EXTEND_RES_TAG)
    }

    // ---------- Outgoing helpers --------------------------------------------- //

    private suspend fun sendResult(topic: String, id: Long, result: JsonElement, ttl: Int, tag: Int) {
        val payload = WcJsonRpc.formatJsonRpcResult(id, result)
        publishOnTopic(topic, payload, ttl, tag)
    }

    private suspend fun sendError(topic: String, id: Long, code: Int, message: String, ttl: Int, tag: Int) {
        val payload = WcJsonRpc.formatJsonRpcError(id, code, message)
        publishOnTopic(topic, payload, ttl, tag)
    }

    private suspend fun publishOnTopic(topic: String, payload: JsonObject, ttl: Int, tag: Int) {
        val envelope = crypto.encodeEnvelope(topic, payload.toString())
        relay.publish(topic, envelope, ttl = ttl, prompt = false, tag = tag)
    }

    private fun isExpired(expirySeconds: Long): Boolean =
        Clock.System.now().toEpochMilliseconds() / 1000L >= expirySeconds

    /**
     * Returns the live session for [topic]. If the session exists but is expired, sends an
     * error response and deletes it, then returns null. Returns null (no error) if unknown.
     */
    private suspend fun liveSession(topic: String, id: Long, ttl: Int, tag: Int): WcSession? {
        val session = synchronized(stateLock) { sessions[topic] } ?: return null
        if (!isExpired(session.expiry)) return session
        sendError(topic, id, ERROR_EXPIRED, "Session expired", ttl, tag)
        deleteSessionLocal(topic)
        return null
    }

    /**
     * True when [method] is approved for [chainId] in the session's namespaces (a method only
     * counts if the namespace covers the chain via an account or an explicit chain entry).
     * Mirrors the SDK's `isValidNamespacesRequest`.
     */
    private fun isApprovedRequest(session: WcSession, chainId: String?, method: String): Boolean =
        session.namespaces.values.any { ns ->
            val coversChain = chainId == null ||
                ns.accounts?.any { it.substringBeforeLast(':') == chainId } == true ||
                ns.chains?.contains(chainId) == true
            coversChain && method in ns.methods
        }

    private suspend fun deleteSessionLocal(topic: String) {
        synchronized(stateLock) { sessions.remove(topic) }
        crypto.deleteSymKey(topic)
        runCatching { sessionRepository.delete(topic) }
        relay.release(topic)
    }

    private suspend fun ensureConnected() {
        var waited = 0L
        while (!relay.isConnected) {
            if (waited >= CONNECT_WAIT_MILLIS) throw WcProtocolException("relay not connected")
            delay(100)
            waited += 100
        }
    }

    // ---------- Namespaces ---------------------------------------------------- //

    private fun computeApprovedNamespaces(
        proposal: WcProposal,
        selectedAccounts: List<String>,
    ): Map<String, WcNamespace> {
        val approved = mutableMapOf<String, WcNamespace>()
        val namespaceKeys = (proposal.requiredNamespaces.keys + proposal.optionalNamespaces.keys).distinct()
        for (ns in namespaceKeys) {
            val req = proposal.requiredNamespaces[ns]
            val opt = proposal.optionalNamespaces[ns]
            // This wallet is EVM-only. A required non-EVM namespace cannot be satisfied, so the
            // settle would be rejected by the dApp's SDK (isConformingNamespaces). Reject early.
            if (ns != EIP155) {
                if (req != null) throw WcProtocolException("required namespace $ns is not supported by this wallet")
                continue
            }
            val requestedChains = (req?.chains.orEmpty() + opt?.chains.orEmpty()).distinct()
            // Only approve accounts that sit on a chain the proposal actually requests.
            val nsAccounts = selectedAccounts.filter { acc ->
                acc.startsWith("$ns:") && (requestedChains.isEmpty() || acc.substringBeforeLast(':') in requestedChains)
            }
            val methods = (req?.methods.orEmpty() + opt?.methods.orEmpty()).distinct()
                .filter { it in SUPPORTED_METHODS }
            val events = (req?.events.orEmpty() + opt?.events.orEmpty()).distinct()
                .filter { it in SUPPORTED_EVENTS }
            if (req != null) {
                // Required namespace: must be fully satisfiable, else the dApp's SDK rejects the
                // settle (isConformingNamespaces needs per-chain accounts + methods/events overlap).
                if (nsAccounts.isEmpty()) throw WcProtocolException("no selected accounts for required namespace $ns")
                if (req.methods.none { it in SUPPORTED_METHODS }) {
                    throw WcProtocolException("no supported methods for required namespace $ns")
                }
                if (req.events.none { it in SUPPORTED_EVENTS }) {
                    throw WcProtocolException("no supported events for required namespace $ns")
                }
                req.chains?.forEach { chain ->
                    if (nsAccounts.none { it.substringBeforeLast(':') == chain }) {
                        throw WcProtocolException("required chain $chain is not covered by the selected accounts")
                    }
                }
            } else {
                // Optional namespace: include only if it has usable accounts and methods/events.
                if (nsAccounts.isEmpty()) continue
                if (methods.isEmpty() && events.isEmpty()) continue
            }
            approved[ns] = WcNamespace(accounts = nsAccounts, methods = methods, events = events)
        }
        if (approved.isEmpty()) throw WcProtocolException("no approvable namespaces for the selected accounts")
        return approved
    }

    // ---------- Persistence --------------------------------------------------- //

    private suspend fun saveSessions() {
        val list = synchronized(stateLock) { sessions.values.toList() }
        for (session in list) {
            // Skip sessions deleted concurrently (symKey gone) to avoid re-inserting a stale row.
            if (!crypto.hasKey(session.topic)) continue
            runCatching { sessionRepository.upsert(session) }
                .onFailure { println("WcSessionManager: failed to save session ${session.topic} — ${it.message}") }
        }
    }

    private suspend fun loadSessions() {
        val loaded = runCatching { sessionRepository.getAll() }.getOrNull() ?: return
        val toTrack = mutableListOf<String>()
        synchronized(stateLock) {
            loaded.forEach { session ->
                if (crypto.hasKey(session.topic) && !isExpired(session.expiry)) {
                    sessions[session.topic] = session
                    toTrack.add(session.topic)
                }
            }
        }
        // Prune rows whose key is gone or that have expired, and track live topics so the
        // relay client resubscribes to them once the socket is (re)established.
        loaded.forEach { session ->
            if (!crypto.hasKey(session.topic) || isExpired(session.expiry)) {
                runCatching { sessionRepository.delete(session.topic) }
            }
        }
        toTrack.forEach { topic -> relay.track(topic) }
    }

    // ---------- JSON helpers -------------------------------------------------- //

    private fun parseMetadata(obj: JsonObject?): WcMetadata {
        if (obj == null) return WcMetadata("", "", "")
        return WcMetadata(
            name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            icons = obj["icons"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        )
    }

    private fun parseNamespace(obj: JsonObject): WcNamespace = WcNamespace(
        chains = obj["chains"]?.jsonArray?.map { it.jsonPrimitive.content },
        accounts = obj["accounts"]?.jsonArray?.map { it.jsonPrimitive.content },
        methods = obj["methods"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        events = obj["events"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
    )

    private fun parseNamespaces(obj: JsonObject?): Map<String, WcNamespace> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, WcNamespace>()
        for ((k, v) in obj) {
            if (v is JsonObject) map[k] = parseNamespace(v)
        }
        return map
    }

    private fun parseError(obj: JsonObject): WcRpcError = WcRpcError(
        code = obj["code"]?.jsonPrimitive?.long?.toInt() ?: ERROR_PARSE,
        message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "unknown error",
    )

    private fun WcMetadata.toJsonObject(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("url", url)
        put("icons", JsonArray(icons.map { JsonPrimitive(it) }))
    }

    private fun Map<String, WcNamespace>.toJsonObject(): JsonObject = buildJsonObject {
        for ((key, ns) in this@toJsonObject) {
            put(key, buildJsonObject {
                ns.accounts?.let { put("accounts", JsonArray(it.map { a -> JsonPrimitive(a) })) }
                ns.chains?.let { put("chains", JsonArray(it.map { c -> JsonPrimitive(c) })) }
                put("methods", JsonArray(ns.methods.map { JsonPrimitive(it) }))
                put("events", JsonArray(ns.events.map { JsonPrimitive(it) }))
            })
        }
    }

    private fun calcExpiry(seconds: Long): Long =
        (Clock.System.now().toEpochMilliseconds() / 1000L) + seconds

    companion object {
        const val EIP155 = "eip155"
        const val RELAY_PROTOCOL = "irn"

        const val ERROR_USER_REJECTED = 5000
        const val ERROR_USER_DISCONNECTED = 6000
        const val ERROR_NOT_SUPPORTED = 5001
        const val ERROR_METHOD_NOT_APPROVED = 5002
        const val ERROR_INTERNAL = 5003
        const val ERROR_PARSE = -32700
        // WC v2 SDK error codes (errors.ts): UNAUTHORIZED_UPDATE_REQUEST=3003, EXPIRED=6.
        const val ERROR_UNAUTHORIZED_UPDATE = 3003
        const val ERROR_EXPIRED = 6

        private const val FIVE_MINUTES = 300
        private const val ONE_DAY = 86_400
        private const val SEVEN_DAYS = 604_800L
        private const val CONNECT_WAIT_MILLIS = 15_000

        private const val PROPOSE_REJECT_TTL = FIVE_MINUTES
        private const val PROPOSE_REJECT_TAG = 1120
        // The `wc_approveSession` relay publish carries a ttl (the SDK's publishCustom
        // defaults it to FIVE_MINUTES); prompt/tag are omitted for this method.
        private const val APPROVE_SESSION_TTL = FIVE_MINUTES
        private const val SESSION_REQUEST_RES_TTL = 900
        private const val SESSION_REQUEST_RES_TAG = 1109
        private const val SESSION_PING_RES_TTL = ONE_DAY
        private const val SESSION_PING_RES_TAG = 1115
        private const val SESSION_PING_REQ_TTL = ONE_DAY
        private const val SESSION_PING_REQ_TAG = 1114
        private const val SESSION_DELETE_REQ_TTL = ONE_DAY
        private const val SESSION_DELETE_REQ_TAG = 1112
        private const val SESSION_DELETE_RES_TTL = ONE_DAY
        private const val SESSION_DELETE_RES_TAG = 1113
        private const val SESSION_UPDATE_RES_TTL = ONE_DAY
        private const val SESSION_UPDATE_RES_TAG = 1105
        private const val SESSION_EXTEND_RES_TTL = ONE_DAY
        private const val SESSION_EXTEND_RES_TAG = 1107

        val SUPPORTED_METHODS = listOf(
            "personal_sign",
            "eth_sign",
            "eth_signTypedData",
            "eth_signTypedData_v3",
            "eth_signTypedData_v4",
            "eth_sendTransaction",
        )
        val SUPPORTED_EVENTS = listOf("accountsChanged", "chainChanged")
    }
}

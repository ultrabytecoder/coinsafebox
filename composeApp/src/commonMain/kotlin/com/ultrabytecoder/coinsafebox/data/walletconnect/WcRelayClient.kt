package com.ultrabytecoder.coinsafebox.data.walletconnect

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock

internal interface WcRelayTransport {
    suspend fun connect(onText: suspend (String) -> Unit): WcRelaySession
}

internal interface WcRelaySession {
    suspend fun sendText(text: String)
    val closed: CompletableDeferred<Unit>
    suspend fun close()
}

internal class KtorWcRelayTransport(
    private val httpClient: HttpClient,
    private val url: String,
    private val scope: CoroutineScope,
) : WcRelayTransport {

    override suspend fun connect(onText: suspend (String) -> Unit): WcRelaySession {
        val session: DefaultClientWebSocketSession = httpClient.webSocketSession(url)
        val closed = CompletableDeferred<Unit>()
        val pump = scope.launch {
            try {
                for (frame in session.incoming) {
                    if (frame is Frame.Text) {
                        onText(frame.data.decodeToString())
                    }
                }
            } finally {
                closed.complete(Unit)
            }
        }
        return object : WcRelaySession {
            override suspend fun sendText(text: String) {
                session.send(Frame.Text(text))
            }

            override val closed: CompletableDeferred<Unit> = closed

            override suspend fun close() {
                session.close()
                pump.cancel()
                closed.complete(Unit)
            }
        }
    }
}

/**
 * Low-level WalletConnect relay client. Handles the WebSocket transport, JSON-RPC
 * request/response correlation (string ids), topic subscribe/unsubscribe, publish,
 * immediate ack of subscription pushes, and message-hash dedupe. Envelopes are
 * decoded via [WcCrypto] and dispatched to the registered [onMessage] handler.
 */
internal class WcRelayClient(
    private val crypto: WcCrypto,
    private val projectId: String,
    private val relayBaseUrl: String = DEFAULT_RELAY_BASE_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
    createClient: () -> HttpClient = { HttpClient { install(WebSockets) { pingIntervalMillis = 30_000 } } },
    internal val transportFactory: ((url: String) -> WcRelayTransport)? = null,
    internal val reconnectDelayMillis: Long = 10_000,
    internal val requestTimeoutMillis: Long = 30_000,
    internal val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val httpClient: HttpClient by lazy { createClient() }
    private val factory: (url: String) -> WcRelayTransport =
        transportFactory ?: { url -> KtorWcRelayTransport(httpClient, url, scope) }

    private val json = Json { ignoreUnknownKeys = true }

    private val stateLock = Any()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonElement>>()
    private var idCounter = 0L

    private val subscriptionsLock = Any()
    private val subscriptions = mutableMapOf<String, String>()

    private val dedupeLock = Any()
    private val seenMessages = LinkedHashSet<String>()

    @Volatile
    private var connectionJob: Job? = null

    @Volatile
    private var activeSession: WcRelaySession? = null

    @Volatile
    private var messageHandler: ((topic: String, payloadJson: String) -> Unit)? = null

    @Volatile
    private var onConnectedHandler: (() -> Unit)? = null

    val isConnected: Boolean get() = activeSession != null

    fun connect() {
        if (projectId.isBlank()) throw WcProtocolException("projectId must not be blank")
        val existing = connectionJob
        if (existing != null && existing.isActive) return
        connectionJob = scope.launch {
            while (isActive) {
                val jwt = crypto.relayAuthJwt(relayBaseUrl)
                val url = buildRelayUrl(jwt)
                var session: WcRelaySession? = null
                try {
                    session = factory(url).connect { text -> handleIncoming(text) }
                    activeSession = session
                    resubscribeAll()
                    runCatching { onConnectedHandler?.invoke() }
                    session.closed.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("WcRelayClient: connection error — ${e.message}")
                } finally {
                    activeSession = null
                    session?.close()
                }
                failAllPending("relay connection lost")
                if (!isActive) break
                delay(reconnectDelayMillis)
            }
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        failAllPending("relay disconnected")
    }

    fun setOnMessage(handler: ((topic: String, payloadJson: String) -> Unit)?) {
        messageHandler = handler
    }

    /** Invoked after each successful (re)connect, once resubscription has run. */
    fun setOnConnected(handler: (() -> Unit)?) {
        onConnectedHandler = handler
    }

    suspend fun publish(topic: String, message: String, ttl: Int = 7, prompt: Boolean = false, tag: Int = 0) {
        requireConnected()
        val params = buildJsonObject {
            put("topic", topic)
            put("message", message)
            put("ttl", ttl)
            put("prompt", prompt)
            put("tag", tag)
        }
        request("irn_publish", params)
    }

    suspend fun subscribe(topic: String): String {
        requireConnected()
        val params = buildJsonObject { put("topic", topic) }
        val result = request("irn_subscribe", params)
        val subId = result?.jsonPrimitive?.contentOrNull
        if (subId.isNullOrEmpty()) throw WcProtocolException("subscribe failed for topic $topic")
        synchronized(subscriptionsLock) { subscriptions[topic] = subId }
        return subId
    }

    suspend fun unsubscribe(topic: String) {
        requireConnected()
        val subId = synchronized(subscriptionsLock) { subscriptions[topic] }
            ?: throw WcProtocolException("no active subscription for topic $topic")
        val params = buildJsonObject {
            put("id", subId)
            put("topic", topic)
        }
        request("irn_unsubscribe", params)
        synchronized(subscriptionsLock) { subscriptions.remove(topic) }
    }

    /**
     * Sends an arbitrary relay-level JSON-RPC request (e.g. `wc_approveSession`,
     * `wc_rejectSession`). Returns the relay's result element.
     */
    suspend fun relayRequest(method: String, params: JsonObject): JsonElement {
        requireConnected()
        return request(method, params)
    }

    private fun requireConnected() {
        if (!isConnected) throw WcProtocolException("relay not connected")
    }

    private fun nextRelayId(): String {
        val base = Clock.System.now().toEpochMilliseconds() * 1000L
        val n = synchronized(stateLock) { idCounter++ % 1000L }
        return (base + n).toString()
    }

    private suspend fun request(method: String, params: JsonObject): JsonElement {
        val id = nextRelayId()
        val deferred = CompletableDeferred<JsonElement>()
        synchronized(stateLock) { pending[id] = deferred }
        val session = activeSession
        if (session == null) {
            synchronized(stateLock) { pending.remove(id) }
            throw WcProtocolException("relay not connected")
        }
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        try {
            session.sendText(body.toString())
        } catch (e: Exception) {
            synchronized(stateLock) { pending.remove(id) }
            throw WcProtocolException("relay send failed: ${e.message}")
        }
        return try {
            withTimeout(requestTimeoutMillis) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            synchronized(stateLock) { pending.remove(id) }
            throw WcProtocolException("relay request timed out")
        } finally {
            synchronized(stateLock) { pending.remove(id) }
        }
    }

    private fun buildRelayUrl(jwt: String): String {
        // Sorted query params, percent-encoded — mirrors formatRelayRpcUrl.
        val params = linkedMapOf(
            "auth" to jwt,
            "projectId" to projectId,
            "ua" to userAgent,
            "useOnCloseEvent" to "true",
        ).toSortedMap()
        val query = params.entries.joinToString("&") { (k, v) ->
            "${WcEncoding.urlEncode(k)}=${WcEncoding.urlEncode(v)}"
        }
        return "$relayBaseUrl?$query"
    }

    private suspend fun handleIncoming(text: String) {
        val payload = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            println("WcRelayClient: ignoring malformed frame — ${e.message}")
            return
        }
        val method = payload["method"]?.jsonPrimitive?.contentOrNull
        if (method != null && method.endsWith(SUBSCRIPTION_SUFFIX)) {
            handleSubscription(payload)
            return
        }
        val id = payload["id"]?.jsonPrimitive?.contentOrNull ?: return
        if (payload.containsKey("result") || payload.containsKey("error")) {
            respond(id, payload)
        }
    }

    private fun respond(id: String, payload: JsonObject) {
        val deferred = synchronized(stateLock) { pending.remove(id) } ?: return
        val error = payload["error"]?.jsonObject
        if (error != null) {
            val code = error["code"]?.jsonPrimitive?.contentOrNull ?: "?"
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "unknown error"
            deferred.completeExceptionally(WcProtocolException("relay error $code: $message"))
        } else {
            deferred.complete(payload["result"] ?: JsonNull)
        }
    }

    private suspend fun handleSubscription(payload: JsonObject) {
        val params = payload["params"]?.jsonObject ?: return
        val data = params["data"]?.jsonObject ?: return
        val topic = data["topic"]?.jsonPrimitive?.contentOrNull
            ?: payload["topic"]?.jsonPrimitive?.contentOrNull
            ?: return
        val envelope = data["message"]?.jsonPrimitive?.contentOrNull ?: return

        // Ack immediately (before dedupe/decode) — mirrors the SDK relay layer.
        val pushId = payload["id"]
        if (pushId != null) {
            runCatching {
                activeSession?.let { session ->
                    val ack = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", pushId)
                        put("result", true)
                    }
                    session.sendText(ack.toString())
                }
            }
        }

        // Dedupe by message hash.
        val hash = crypto.hashMessage(envelope)
        synchronized(dedupeLock) {
            if (!seenMessages.add(hash)) return
            if (seenMessages.size > MAX_DEDUPE) seenMessages.remove(seenMessages.first())
        }

        val payloadJson = try {
            crypto.decodeEnvelope(topic, envelope)
        } catch (e: Exception) {
            println("WcRelayClient: envelope decode failed for topic $topic — ${e.message}")
            return
        }
        runCatching { messageHandler?.invoke(topic, payloadJson) }
            .onFailure { println("WcRelayClient: message handler failed — ${it.message}") }
    }

    private fun failAllPending(message: String) {
        val orphans = synchronized(stateLock) {
            val orphans = pending.toMap()
            pending.clear()
            orphans
        }
        orphans.values.forEach { it.completeExceptionally(WcProtocolException(message)) }
    }

    private suspend fun resubscribeAll() {
        val snapshot = synchronized(subscriptionsLock) { subscriptions.toMap() }
        for ((topic, _) in snapshot) {
            runCatching {
                val subId = subscribe(topic)
                synchronized(subscriptionsLock) { subscriptions[topic] = subId }
            }.onFailure {
                println("WcRelayClient: resubscribe failed for topic $topic — ${it.message}")
            }
        }
    }

    companion object {
        const val DEFAULT_RELAY_BASE_URL = "wss://relay.walletconnect.org"
        const val DEFAULT_USER_AGENT = "wc-2/coinsafebox-1.0.0/kmp"
        private const val SUBSCRIPTION_SUFFIX = "_subscription"
        private const val MAX_DEDUPE = 1000
    }
}

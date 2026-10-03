package com.ultrabytecoder.coinsafebox.data.walletconnect

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock

/** Shared fakes/builders for WalletConnect ViewModel tests (mirrors WcSessionManagerTest). */

internal class InMemoryWcKeyChain : WcKeyChain {
    private val map = mutableMapOf<String, String>()
    override fun has(tag: String): Boolean = map.containsKey(tag)
    override fun get(tag: String): String? = map[tag]
    override fun set(tag: String, value: String) { map[tag] = value }
    override fun delete(tag: String) { map.remove(tag) }
    override fun loadAll(): Map<String, String> = map.toMap()
}

internal class FakeWcSessionRepositoryForTest : WcSessionRepository {
    private val lock = Any()
    private val map = mutableMapOf<String, WcSession>()
    override suspend fun getAll(): List<WcSession> = synchronized(lock) { map.values.toList() }
    override suspend fun get(topic: String): WcSession? = synchronized(lock) { map[topic] }
    override suspend fun upsert(session: WcSession) { synchronized(lock) { map[session.topic] = session } }
    override suspend fun delete(topic: String) { synchronized(lock) { map.remove(topic) } }
}

/** Relay fake that auto-responds to JSON-RPC requests (subscribe/publish/wc_*). */
internal class AutoWcRelayTransport : WcRelayTransport {
    private val lock = Any()
    private val sentList = mutableListOf<JsonObject>()
    private val parse = Json { ignoreUnknownKeys = true }
    private var textHandler: (suspend (String) -> Unit)? = null
    private var subCounter = 0

    val sent: List<JsonObject> get() = synchronized(lock) { sentList.toList() }
    fun frames(method: String): List<JsonObject> = sent.filter { it["method"]?.jsonPrimitive?.content == method }

    override suspend fun connect(onText: suspend (String) -> Unit): WcRelaySession {
        textHandler = onText
        return object : WcRelaySession {
            override val closed = CompletableDeferred<Unit>()
            override suspend fun sendText(text: String) {
                val obj = parse.parseToJsonElement(text).jsonObject
                synchronized(lock) { sentList.add(obj) }
                autoRespond(obj)
            }
            override suspend fun close() { closed.complete(Unit) }
        }
    }

    private suspend fun autoRespond(obj: JsonObject) {
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
        val id = obj["id"]
        if (method == null || id == null) return
        val result: String = when (method) {
            "irn_subscribe" -> "\"sub-${synchronized(lock) { ++subCounter }}\""
            else -> "true"
        }
        textHandler?.invoke("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
    }

    suspend fun pushTopic(topic: String, envelope: String, pushId: String) {
        textHandler?.invoke(
            """{"jsonrpc":"2.0","id":"$pushId","topic":"$topic","method":"irn_subscription","params":{"id":"sub-1","data":{"topic":"$topic","message":"$envelope","publishedAt":1700000000}}}"""
        )
    }
}

internal fun newTestWcCrypto(): WcCrypto {
    val c = WcCrypto(InMemoryWcKeyChain())
    c.init()
    return c
}

internal fun buildTestWcRelay(
    crypto: WcCrypto,
    transport: AutoWcRelayTransport,
    scheduler: TestCoroutineScheduler,
): WcRelayClient = WcRelayClient(
    crypto = crypto,
    projectId = "test-project-id",
    transportFactory = { transport },
    reconnectDelayMillis = 10,
    requestTimeoutMillis = 1000,
    dispatcher = UnconfinedTestDispatcher(scheduler),
)

internal fun buildTestWcSessionManager(
    crypto: WcCrypto,
    relay: WcRelayClient,
    scheduler: TestCoroutineScheduler,
    repository: WcSessionRepository = FakeWcSessionRepositoryForTest(),
): WcSessionManager = WcSessionManager(
    crypto = crypto,
    relay = relay,
    walletMetadata = WcMetadata("CoinSafeBox", "test wallet", "https://example.com"),
    supportedChainIds = listOf(1L),
    requestHandler = null,
    sessionRepository = repository,
    dispatcher = UnconfinedTestDispatcher(scheduler),
)

/** Simulates the dApp: creates a pairing topic + URI. */
internal fun dappPairingForTest(crypto: WcCrypto): Pair<String, String> {
    val symKey = WcEncoding.hexEncode(crypto.generateRandomBytes32())
    val topic = crypto.hashKey(symKey)
    val uri = "wc:$topic@2?relay-protocol=irn&symKey=$symKey"
    return topic to uri
}

/** Builds the encrypted `wc_sessionPropose` envelope a dApp would publish on the pairing topic. */
internal fun proposeEnvelopeForTest(
    crypto: WcCrypto,
    pairingTopic: String,
    proposerPubHex: String,
    proposalId: Long,
    chains: List<String> = listOf("eip155:1"),
    expiryOffsetSeconds: Long = 300,
): String {
    val params = buildJsonObject {
        put("id", proposalId)
        put("proposer", buildJsonObject {
            put("publicKey", proposerPubHex)
            put("metadata", buildJsonObject {
                put("name", "Test DApp")
                put("description", "dapp")
                put("url", "https://dapp.example")
                put("icons", JsonArray(emptyList()))
            })
        })
        put("requiredNamespaces", buildJsonObject {
            put("eip155", buildJsonObject {
                put("chains", JsonArray(chains.map { JsonPrimitive(it) }))
                put("methods", JsonArray(listOf(JsonPrimitive("personal_sign"), JsonPrimitive("eth_sendTransaction"))))
                put("events", JsonArray(listOf(JsonPrimitive("accountsChanged"))))
            })
        })
        put("optionalNamespaces", buildJsonObject {})
        put("expiry", (Clock.System.now().toEpochMilliseconds() / 1000 + expiryOffsetSeconds).toInt())
    }
    val request = WcJsonRpc.formatJsonRpcRequest("wc_sessionPropose", params, proposalId)
    return crypto.encodeEnvelope(pairingTopic, request.toString())
}

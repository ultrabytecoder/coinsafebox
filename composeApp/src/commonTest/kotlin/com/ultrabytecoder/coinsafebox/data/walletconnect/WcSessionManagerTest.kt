package com.ultrabytecoder.coinsafebox.data.walletconnect

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WcSessionManagerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class InMemoryKeyChain : WcKeyChain {
        private val map = mutableMapOf<String, String>()
        override fun has(tag: String): Boolean = map.containsKey(tag)
        override fun get(tag: String): String? = map[tag]
        override fun set(tag: String, value: String) { map[tag] = value }
        override fun delete(tag: String) { map.remove(tag) }
        override fun loadAll(): Map<String, String> = map.toMap()
    }

    private class FakeWcSessionRepository : WcSessionRepository {
        private val lock = Any()
        private val map = mutableMapOf<String, WcSession>()
        override suspend fun getAll(): List<WcSession> = synchronized(lock) { map.values.toList() }
        override suspend fun get(topic: String): WcSession? = synchronized(lock) { map[topic] }
        override suspend fun upsert(session: WcSession) { synchronized(lock) { map[session.topic] = session } }
        override suspend fun delete(topic: String) { synchronized(lock) { map.remove(topic) } }
    }

    private class FakeHandler : WcSessionRequestHandler {
        var lastRequest: WcSessionRequest? = null
        var callCount = 0
        override suspend fun handleRequest(session: WcSession, request: WcSessionRequest): WcRequestOutcome {
            lastRequest = request
            callCount++
            return WcRequestOutcome.Success(JsonPrimitive("0xdeadbeef"))
        }
    }

    /** Relay fake that auto-responds to JSON-RPC requests (subscribe/publish/wc_*). */
    private class AutoRelayTransport : WcRelayTransport {
        private val lock = Any()
        private val sentList = mutableListOf<JsonObject>()
        private val parse = Json { ignoreUnknownKeys = true }
        private var textHandler: (suspend (String) -> Unit)? = null
        private var currentSession: WcRelaySession? = null
        private var subCounter = 0

        val sent: List<JsonObject> get() = synchronized(lock) { sentList.toList() }
        fun frames(method: String): List<JsonObject> = sent.filter { it["method"]?.jsonPrimitive?.content == method }

        override suspend fun connect(onText: suspend (String) -> Unit): WcRelaySession {
            textHandler = onText
            val session = object : WcRelaySession {
                override val closed = CompletableDeferred<Unit>()
                override suspend fun sendText(text: String) {
                    val obj = parse.parseToJsonElement(text).jsonObject
                    synchronized(lock) { sentList.add(obj) }
                    autoRespond(obj)
                }
                override suspend fun close() { closed.complete(Unit) }
            }
            currentSession = session
            return session
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

    private fun newCrypto(): WcCrypto {
        val c = WcCrypto(InMemoryKeyChain())
        c.init()
        return c
    }

    private fun buildManager(
        crypto: WcCrypto,
        relay: WcRelayClient,
        handler: WcSessionRequestHandler?,
        scheduler: TestCoroutineScheduler,
        repository: WcSessionRepository = FakeWcSessionRepository(),
    ): WcSessionManager = WcSessionManager(
        crypto = crypto,
        relay = relay,
        walletMetadata = WcMetadata("CoinSafeBox", "test wallet", "https://example.com"),
        supportedChainIds = listOf(1L),
        requestHandler = handler,
        sessionRepository = repository,
        dispatcher = UnconfinedTestDispatcher(scheduler),
    )

    private fun buildRelay(crypto: WcCrypto, relay: AutoRelayTransport, scheduler: TestCoroutineScheduler): WcRelayClient =
        WcRelayClient(
            crypto = crypto,
            projectId = "test-project-id",
            transportFactory = { relay },
            reconnectDelayMillis = 10,
            requestTimeoutMillis = 1000,
            dispatcher = UnconfinedTestDispatcher(scheduler),
        )

    private suspend fun <T> awaitValue(timeout: Long = 2000, block: () -> T?): T {
        var waited = 0L
        while (waited < timeout) {
            block()?.let { return it }
            delay(1)
            waited++
        }
        error("timed out awaiting value")
    }

    /** Simulates the dApp: creates a pairing topic + URI. */
    private fun dappPairing(crypto: WcCrypto): Pair<String, String> {
        val symKey = WcEncoding.hexEncode(crypto.generateRandomBytes32())
        val topic = crypto.hashKey(symKey)
        val uri = "wc:$topic@2?relay-protocol=irn&symKey=$symKey"
        return topic to uri
    }

    /** Simulates the dApp publishing a wc_sessionPropose on the pairing topic. */
    private fun proposeEnvelope(
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
                    put("icons", kotlinx.serialization.json.JsonArray(emptyList()))
                })
            })
            put("requiredNamespaces", buildJsonObject {
                put("eip155", buildJsonObject {
                    put("chains", kotlinx.serialization.json.JsonArray(chains.map { JsonPrimitive(it) }))
                    put("methods", kotlinx.serialization.json.JsonArray(
                        listOf(JsonPrimitive("personal_sign"), JsonPrimitive("eth_sendTransaction"))
                    ))
                    put("events", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("accountsChanged"))))
                })
            })
            put("optionalNamespaces", buildJsonObject {})
            put("expiry", (kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000 + expiryOffsetSeconds).toInt())
        }
        val request = WcJsonRpc.formatJsonRpcRequest("wc_sessionPropose", params, proposalId)
        return crypto.encodeEnvelope(pairingTopic, request.toString())
    }

    @Test
    fun fullFlow_pairProposeApproveSettleRequestDelete() = runTest {
        val crypto = newCrypto()
        val relay = AutoRelayTransport()
        val relayClient = buildRelay(crypto, relay, testScheduler)
        val handler = FakeHandler()
        val manager = buildManager(crypto, relayClient, handler, testScheduler)
        manager.start()
        assertTrue(relayClient.isConnected)

        // 1. Pair
        val (pairingTopic, uri) = dappPairing(crypto)
        val pairing = manager.pair(uri)
        assertEquals(pairingTopic, pairing.topic)
        assertTrue(relay.frames("irn_subscribe").any {
            it["params"]!!.jsonObject["topic"]!!.jsonPrimitive.content == pairingTopic
        })

        // 2. dApp proposes
        val proposerKeyPair = crypto.generateX25519KeyPair()
        val proposerPubHex = WcEncoding.hexEncode(proposerKeyPair.publicKey)
        val proposalDeferred = CompletableDeferred<WcProposal>()
        manager.onProposal = { proposalDeferred.complete(it) }
        val proposalId = 1001L
        relay.pushTopic(pairingTopic, proposeEnvelope(crypto, pairingTopic, proposerPubHex, proposalId), "p1")
        val proposal = proposalDeferred.await()
        assertEquals(proposalId, proposal.id)
        assertEquals(proposerPubHex, proposal.proposerPublicKey)
        assertEquals("Test DApp", proposal.proposerMetadata.name)

        // 3. Approve
        val account = "eip155:1:0x1111111111111111111111111111111111111111"
        val session = manager.approve(proposalId, listOf(account))
        assertNotNull(session.settleRequestId)

        val approveReq = relay.frames("wc_approveSession").first()
        val approveParams = approveReq["params"]!!.jsonObject
        assertEquals(session.topic, approveParams["sessionTopic"]!!.jsonPrimitive.content)
        assertEquals(pairingTopic, approveParams["pairingTopic"]!!.jsonPrimitive.content)
        // The settlement envelope must decrypt to a wc_sessionSettle request with our namespaces.
        val settleEnvelope = approveParams["sessionSettlementRequest"]!!.jsonPrimitive.content
        val settleJson = json.parseToJsonElement(crypto.decodeEnvelope(session.topic, settleEnvelope)).jsonObject
        assertEquals("wc_sessionSettle", settleJson["method"]!!.jsonPrimitive.content)
        val settleNamespaces = settleJson["params"]!!.jsonObject["namespaces"]!!.jsonObject
        val eip155 = settleNamespaces["eip155"]!!.jsonObject
        assertEquals(1, eip155["accounts"]!!.jsonArray.size)
        assertEquals(account, eip155["accounts"]!!.jsonArray.first().jsonPrimitive.content)

        // 4. dApp settles (responds to wc_sessionSettle)
        val approvedDeferred = CompletableDeferred<WcSession>()
        manager.onSessionApproved = { approvedDeferred.complete(it) }
        val settleResult = WcJsonRpc.formatJsonRpcResult(session.settleRequestId!!, JsonPrimitive(true))
        relay.pushTopic(session.topic, crypto.encodeEnvelope(session.topic, settleResult.toString()), "s1")
        val settled = approvedDeferred.await()
        assertTrue(settled.acknowledged)
        assertEquals(session.topic, settled.topic)
        assertNotNull(manager.getSession(session.topic))

        // 5. dApp sends a session request -> handler fulfils it
        val requestId = 2002L
        val sessionRequest = WcJsonRpc.formatJsonRpcRequest(
            "wc_sessionRequest",
            buildJsonObject {
                put("request", buildJsonObject {
                    put("method", "personal_sign")
                    put("params", buildJsonObject {
                        put("message", "0x1234")
                        put("from", account)
                    })
                })
                put("chainId", "eip155:1")
            },
            requestId,
        )
        relay.pushTopic(session.topic, crypto.encodeEnvelope(session.topic, sessionRequest.toString()), "s2")
        awaitValue { handler.lastRequest }
        assertEquals(requestId, handler.lastRequest!!.id)
        assertEquals("personal_sign", handler.lastRequest!!.requestMethod)
        assertEquals("eip155:1", handler.lastRequest!!.chainId)

        val responsePublish = relay.frames("irn_publish").last {
            it["params"]!!.jsonObject["topic"]!!.jsonPrimitive.content == session.topic
        }
        val respEnvelope = responsePublish["params"]!!.jsonObject["message"]!!.jsonPrimitive.content
        val respJson = json.parseToJsonElement(crypto.decodeEnvelope(session.topic, respEnvelope)).jsonObject
        assertEquals(requestId, respJson["id"]!!.jsonPrimitive.long)
        assertEquals("0xdeadbeef", respJson["result"]!!.jsonPrimitive.content)

        // 6. dApp deletes the session
        val deletedDeferred = CompletableDeferred<String>()
        manager.onSessionDeleted = { topic, _ -> deletedDeferred.complete(topic) }
        val deleteId = 3003L
        val deleteRequest = WcJsonRpc.formatJsonRpcRequest(
            "wc_sessionDelete",
            buildJsonObject { put("code", 6000); put("message", "dapp closed") },
            deleteId,
        )
        relay.pushTopic(session.topic, crypto.encodeEnvelope(session.topic, deleteRequest.toString()), "s3")
        assertEquals(session.topic, deletedDeferred.await())
        assertNull(manager.getSession(session.topic))
        assertFalse(crypto.hasKey(session.topic))

        manager.stop()
    }

    @Test
    fun rejectPublishesErrorOnPairingTopicAndClearsProposal() = runTest {
        val crypto = newCrypto()
        val relay = AutoRelayTransport()
        val relayClient = buildRelay(crypto, relay, testScheduler)
        val manager = buildManager(crypto, relayClient, null, testScheduler)
        manager.start()

        val (pairingTopic, uri) = dappPairing(crypto)
        manager.pair(uri)
        val proposerKeyPair = crypto.generateX25519KeyPair()
        val proposerPubHex = WcEncoding.hexEncode(proposerKeyPair.publicKey)
        val proposalDeferred = CompletableDeferred<WcProposal>()
        manager.onProposal = { proposalDeferred.complete(it) }
        val proposalId = 4004L
        relay.pushTopic(pairingTopic, proposeEnvelope(crypto, pairingTopic, proposerPubHex, proposalId), "p1")
        val proposal = proposalDeferred.await()
        assertEquals(proposalId, proposal.id)

        manager.reject(proposalId, WcRpcError(WcSessionManager.ERROR_USER_REJECTED, "Nope"))

        val publish = relay.frames("irn_publish").first {
            it["params"]!!.jsonObject["topic"]!!.jsonPrimitive.content == pairingTopic
        }
        val params = publish["params"]!!.jsonObject
        assertEquals(1120, params["tag"]!!.jsonPrimitive.long.toInt())
        val envelope = params["message"]!!.jsonPrimitive.content
        val errorJson = json.parseToJsonElement(crypto.decodeEnvelope(pairingTopic, envelope)).jsonObject
        assertEquals(proposalId, errorJson["id"]!!.jsonPrimitive.long)
        assertEquals("Nope", errorJson["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertNull(manager.getProposal(proposalId))

        manager.stop()
    }

    @Test
    fun approveWithUnsupportedAccountsThrows() = runTest {
        val crypto = newCrypto()
        val relay = AutoRelayTransport()
        val relayClient = buildRelay(crypto, relay, testScheduler)
        val manager = buildManager(crypto, relayClient, null, testScheduler)
        manager.start()

        val (pairingTopic, uri) = dappPairing(crypto)
        manager.pair(uri)
        val proposerKeyPair = crypto.generateX25519KeyPair()
        val proposerPubHex = WcEncoding.hexEncode(proposerKeyPair.publicKey)
        val proposalDeferred = CompletableDeferred<WcProposal>()
        manager.onProposal = { proposalDeferred.complete(it) }
        val proposalId = 5005L
        relay.pushTopic(pairingTopic, proposeEnvelope(crypto, pairingTopic, proposerPubHex, proposalId), "p1")
        proposalDeferred.await()

        // Account on a chain the proposal doesn't request -> no approvable namespace.
        val ex = runCatching { manager.approve(proposalId, listOf("eip155:5:0xabc")) }.exceptionOrNull()
        assertTrue(ex is WcProtocolException, "expected WcProtocolException, got $ex")

        manager.stop()
    }

    @Test
    fun sessionsPersistAcrossRestart() = runTest {
        val repository = FakeWcSessionRepository()
        val crypto = newCrypto()

        // First "app run": pair + approve + settle, then stop.
        val relay = AutoRelayTransport()
        val relayClient = buildRelay(crypto, relay, testScheduler)
        val manager = buildManager(crypto, relayClient, null, testScheduler, repository)
        manager.start()
        val (pairingTopic, uri) = dappPairing(crypto)
        manager.pair(uri)
        val proposerKeyPair = crypto.generateX25519KeyPair()
        val proposerPubHex = WcEncoding.hexEncode(proposerKeyPair.publicKey)
        val proposalDeferred = CompletableDeferred<WcProposal>()
        manager.onProposal = { proposalDeferred.complete(it) }
        val proposalId = 6006L
        relay.pushTopic(pairingTopic, proposeEnvelope(crypto, pairingTopic, proposerPubHex, proposalId), "p1")
        val proposal = proposalDeferred.await()
        val session = manager.approve(proposalId, listOf("eip155:1:0x2222222222222222222222222222222222222222"))
        val sessionTopic = session.topic
        val settleResult = WcJsonRpc.formatJsonRpcResult(session.settleRequestId!!, JsonPrimitive(true))
        relay.pushTopic(session.topic, crypto.encodeEnvelope(session.topic, settleResult.toString()), "s1")
        awaitValue { manager.getSession(sessionTopic)?.let { if (it.acknowledged) it else null } }
        manager.stop()

        // Second "app run": same crypto (keychain) + repository; a fresh relay/manager must reload the session.
        val relay2 = AutoRelayTransport()
        val relayClient2 = buildRelay(crypto, relay2, testScheduler)
        val manager2 = buildManager(crypto, relayClient2, null, testScheduler, repository)
        manager2.start()
        val reloaded = awaitValue { manager2.getSession(sessionTopic) }
        assertNotNull(reloaded)
        assertTrue(reloaded.acknowledged)
        // It must also resubscribe to the session topic on (re)connect.
        assertTrue(relay2.frames("irn_subscribe").any {
            it["params"]!!.jsonObject["topic"]!!.jsonPrimitive.content == sessionTopic
        })
        manager2.stop()
    }

    private class Settled(
        val session: WcSession,
        val crypto: WcCrypto,
        val relay: AutoRelayTransport,
        val manager: WcSessionManager,
        val account: String,
    )

    /** Runs the full pair→propose→approve→settle flow and returns the established session. */
    private suspend fun settledSession(
        scheduler: TestCoroutineScheduler,
        handler: WcSessionRequestHandler? = null,
    ): Settled {
        val crypto = newCrypto()
        val relay = AutoRelayTransport()
        val relayClient = buildRelay(crypto, relay, scheduler)
        val manager = buildManager(crypto, relayClient, handler, scheduler)
        manager.start()
        val (pairingTopic, uri) = dappPairing(crypto)
        manager.pair(uri)
        val proposerKeyPair = crypto.generateX25519KeyPair()
        val proposerPubHex = WcEncoding.hexEncode(proposerKeyPair.publicKey)
        val proposalDeferred = CompletableDeferred<WcProposal>()
        manager.onProposal = { proposalDeferred.complete(it) }
        val proposalId = 9001L
        relay.pushTopic(pairingTopic, proposeEnvelope(crypto, pairingTopic, proposerPubHex, proposalId), "p1")
        proposalDeferred.await()
        val account = "eip155:1:0x3333333333333333333333333333333333333333"
        val session = manager.approve(proposalId, listOf(account))
        val approvedDeferred = CompletableDeferred<WcSession>()
        manager.onSessionApproved = { approvedDeferred.complete(it) }
        val settleResult = WcJsonRpc.formatJsonRpcResult(session.settleRequestId!!, JsonPrimitive(true))
        relay.pushTopic(session.topic, crypto.encodeEnvelope(session.topic, settleResult.toString()), "s1")
        approvedDeferred.await()
        return Settled(session, crypto, relay, manager, account)
    }

    @Test
    fun approveRejectsWhenRequiredChainNotCovered() = runTest {
        val crypto = newCrypto()
        val relay = AutoRelayTransport()
        val relayClient = buildRelay(crypto, relay, testScheduler)
        val manager = buildManager(crypto, relayClient, null, testScheduler)
        manager.start()
        val (pairingTopic, uri) = dappPairing(crypto)
        manager.pair(uri)
        val proposerKeyPair = crypto.generateX25519KeyPair()
        val proposerPubHex = WcEncoding.hexEncode(proposerKeyPair.publicKey)
        val proposalDeferred = CompletableDeferred<WcProposal>()
        manager.onProposal = { proposalDeferred.complete(it) }
        val proposalId = 9301L
        relay.pushTopic(
            pairingTopic,
            proposeEnvelope(crypto, pairingTopic, proposerPubHex, proposalId, chains = listOf("eip155:1", "eip155:137")),
            "p1",
        )
        proposalDeferred.await()

        // Requires both eip155:1 and eip155:137, but only a chain-1 account is selected.
        val ex = runCatching {
            manager.approve(proposalId, listOf("eip155:1:0x1111111111111111111111111111111111111111"))
        }.exceptionOrNull()
        assertTrue(ex is WcProtocolException, "expected WcProtocolException, got $ex")
        manager.stop()
    }

    @Test
    fun dappSessionUpdateIsRejected() = runTest {
        val s = settledSession(testScheduler)
        val sessionTopic = s.session.topic
        val originalNamespaces = s.manager.getSession(sessionTopic)!!.namespaces
        val updateId = 9401L
        val updateRequest = WcJsonRpc.formatJsonRpcRequest(
            "wc_sessionUpdate",
            buildJsonObject {
                put("namespaces", buildJsonObject {
                    put("eip155", buildJsonObject {
                        put("accounts", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("eip155:1:0x9999"))))
                        put("methods", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("personal_sign"))))
                        put("events", kotlinx.serialization.json.JsonArray(emptyList()))
                    })
                })
            },
            updateId,
        )
        s.relay.pushTopic(sessionTopic, s.crypto.encodeEnvelope(sessionTopic, updateRequest.toString()), "u1")
        val publish = awaitValue {
            s.relay.frames("irn_publish").lastOrNull {
                it["params"]!!.jsonObject["topic"]!!.jsonPrimitive.content == sessionTopic
            }
        }
        val envelope = publish["params"]!!.jsonObject["message"]!!.jsonPrimitive.content
        val respJson = json.parseToJsonElement(s.crypto.decodeEnvelope(sessionTopic, envelope)).jsonObject
        assertEquals(updateId, respJson["id"]!!.jsonPrimitive.long)
        val error = respJson["error"]!!.jsonObject
        assertEquals(3003, error["code"]!!.jsonPrimitive.long.toInt())
        assertEquals(originalNamespaces, s.manager.getSession(sessionTopic)!!.namespaces)
        s.manager.stop()
    }

    @Test
    fun approveRejectsExpiredProposal() = runTest {
        val crypto = newCrypto()
        val relay = AutoRelayTransport()
        val relayClient = buildRelay(crypto, relay, testScheduler)
        val manager = buildManager(crypto, relayClient, null, testScheduler)
        manager.start()
        val (pairingTopic, uri) = dappPairing(crypto)
        manager.pair(uri)
        val proposerKeyPair = crypto.generateX25519KeyPair()
        val proposerPubHex = WcEncoding.hexEncode(proposerKeyPair.publicKey)
        val proposalDeferred = CompletableDeferred<WcProposal>()
        manager.onProposal = { proposalDeferred.complete(it) }
        val proposalId = 9501L
        relay.pushTopic(
            pairingTopic,
            proposeEnvelope(crypto, pairingTopic, proposerPubHex, proposalId, expiryOffsetSeconds = -10),
            "p1",
        )
        proposalDeferred.await()

        val ex = runCatching {
            manager.approve(proposalId, listOf("eip155:1:0x1111111111111111111111111111111111111111"))
        }.exceptionOrNull()
        assertTrue(ex is WcProtocolException, "expected WcProtocolException for expired proposal, got $ex")
        assertNull(manager.getProposal(proposalId))
        manager.stop()
    }

    @Test
    fun expiredSessionsArePrunedOnLoad() = runTest {
        val repository = FakeWcSessionRepository()
        val crypto = newCrypto()
        val topic = "0" + "1".repeat(63)
        // Give the crypto a key for the topic so it is a load candidate, but set a past expiry.
        crypto.setSymKey(crypto.generateRandomBytes32(), topic)
        repository.upsert(
            WcSession(
                topic = topic,
                pairingTopic = "pairing",
                proposerPublicKey = "0xabc",
                proposerMetadata = WcMetadata("d", "d", "https://d"),
                responderPublicKey = "0xdef",
                responderMetadata = WcMetadata("w", "w", "https://w"),
                namespaces = emptyMap(),
                expiry = (kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000 - 10),
                acknowledged = true,
                settleRequestId = null,
            )
        )

        val relay = AutoRelayTransport()
        val relayClient = buildRelay(crypto, relay, testScheduler)
        val manager = buildManager(crypto, relayClient, null, testScheduler, repository)
        manager.start()

        // An expired session must not be loaded, and must be pruned from the repository.
        assertNull(manager.getSession(topic))
        assertNull(repository.get(topic))
        manager.stop()
    }

    @Test
    fun approveRejectsRequiredNonEvmNamespace() = runTest {
        val crypto = newCrypto()
        val relay = AutoRelayTransport()
        val relayClient = buildRelay(crypto, relay, testScheduler)
        val manager = buildManager(crypto, relayClient, null, testScheduler)
        manager.start()
        val (pairingTopic, uri) = dappPairing(crypto)
        manager.pair(uri)
        val proposerKeyPair = crypto.generateX25519KeyPair()
        val proposerPubHex = WcEncoding.hexEncode(proposerKeyPair.publicKey)
        val proposalDeferred = CompletableDeferred<WcProposal>()
        manager.onProposal = { proposalDeferred.complete(it) }
        val proposalId = 9601L

        // Requires both eip155 and a required cosmos namespace; the wallet is EVM-only.
        val nowSec = (kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000).toInt()
        val params = buildJsonObject {
            put("id", proposalId)
            put("proposer", buildJsonObject {
                put("publicKey", proposerPubHex)
                put("metadata", buildJsonObject { put("name", "D"); put("description", "d"); put("url", "https://d") })
            })
            put("requiredNamespaces", buildJsonObject {
                put("eip155", buildJsonObject {
                    put("chains", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("eip155:1"))))
                    put("methods", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("personal_sign"))))
                    put("events", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("accountsChanged"))))
                })
                put("cosmos", buildJsonObject {
                    put("chains", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("cosmos:cosmoshub-4"))))
                    put("methods", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("cosmos_sign"))))
                    put("events", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("chainChanged"))))
                })
            })
            put("optionalNamespaces", buildJsonObject {})
            put("expiry", nowSec + 300)
        }
        val request = WcJsonRpc.formatJsonRpcRequest("wc_sessionPropose", params, proposalId)
        relay.pushTopic(pairingTopic, crypto.encodeEnvelope(pairingTopic, request.toString()), "p1")
        proposalDeferred.await()

        // Even with a valid eip155 account selected, the required cosmos namespace is unsatisfiable.
        val ex = runCatching {
            manager.approve(proposalId, listOf("eip155:1:0x1111111111111111111111111111111111111111"))
        }.exceptionOrNull()
        assertTrue(ex is WcProtocolException, "expected WcProtocolException for required non-EVM namespace, got $ex")
        manager.stop()
    }

    @Test
    fun sessionRequestOnUnapprovedChainIsRejected() = runTest {
        val handler = FakeHandler()
        val s = settledSession(testScheduler, handler)
        val sessionTopic = s.session.topic
        // The session only covers eip155:1; a request on eip155:137 must be rejected and must
        // never reach the request handler.
        val requestId = 9701L
        val request = WcJsonRpc.formatJsonRpcRequest(
            "wc_sessionRequest",
            buildJsonObject {
                put("request", buildJsonObject {
                    put("method", "personal_sign")
                    put("params", buildJsonObject { put("message", "0x1234") })
                })
                put("chainId", "eip155:137")
            },
            requestId,
        )
        s.relay.pushTopic(sessionTopic, s.crypto.encodeEnvelope(sessionTopic, request.toString()), "r1")
        val publish = awaitValue {
            s.relay.frames("irn_publish").lastOrNull {
                it["params"]!!.jsonObject["topic"]!!.jsonPrimitive.content == sessionTopic
            }
        }
        val envelope = publish["params"]!!.jsonObject["message"]!!.jsonPrimitive.content
        val respJson = json.parseToJsonElement(s.crypto.decodeEnvelope(sessionTopic, envelope)).jsonObject
        assertEquals(requestId, respJson["id"]!!.jsonPrimitive.long)
        assertEquals(5002, respJson["error"]!!.jsonObject["code"]!!.jsonPrimitive.long.toInt())
        assertEquals(0, handler.callCount, "handler must not be invoked for an unapproved chain")
        s.manager.stop()
    }

    @Test
    fun sessionRequestWithUnapprovedMethodIsRejected() = runTest {
        val handler = FakeHandler()
        val s = settledSession(testScheduler, handler)
        val sessionTopic = s.session.topic
        // eth_signCustom is not in SUPPORTED_METHODS, so it was never approved in the namespace.
        val requestId = 9702L
        val request = WcJsonRpc.formatJsonRpcRequest(
            "wc_sessionRequest",
            buildJsonObject {
                put("request", buildJsonObject {
                    put("method", "eth_signCustom")
                    put("params", buildJsonObject { put("message", "0x1234") })
                })
                put("chainId", "eip155:1")
            },
            requestId,
        )
        s.relay.pushTopic(sessionTopic, s.crypto.encodeEnvelope(sessionTopic, request.toString()), "r2")
        val publish = awaitValue {
            s.relay.frames("irn_publish").lastOrNull {
                it["params"]!!.jsonObject["topic"]!!.jsonPrimitive.content == sessionTopic
            }
        }
        val envelope = publish["params"]!!.jsonObject["message"]!!.jsonPrimitive.content
        val respJson = json.parseToJsonElement(s.crypto.decodeEnvelope(sessionTopic, envelope)).jsonObject
        assertEquals(requestId, respJson["id"]!!.jsonPrimitive.long)
        assertEquals(5002, respJson["error"]!!.jsonObject["code"]!!.jsonPrimitive.long.toInt())
        assertEquals(0, handler.callCount, "handler must not be invoked for an unapproved method")
        s.manager.stop()
    }

    @Test
    fun extendWithPastExpiryDoesNotShrinkSession() = runTest {
        val s = settledSession(testScheduler)
        val sessionTopic = s.session.topic
        val originalExpiry = s.manager.getSession(sessionTopic)!!.expiry
        // A malicious/buggy dApp sends a past expiry (year 2000). The session must not be
        // shrunk to (or killed by) that value.
        val extendId = 9801L
        val request = WcJsonRpc.formatJsonRpcRequest(
            "wc_sessionExtend",
            buildJsonObject { put("expiry", 946684800) },
            extendId,
        )
        s.relay.pushTopic(sessionTopic, s.crypto.encodeEnvelope(sessionTopic, request.toString()), "e1")
        val publish = awaitValue {
            s.relay.frames("irn_publish").lastOrNull {
                it["params"]!!.jsonObject["topic"]!!.jsonPrimitive.content == sessionTopic
            }
        }
        val envelope = publish["params"]!!.jsonObject["message"]!!.jsonPrimitive.content
        val respJson = json.parseToJsonElement(s.crypto.decodeEnvelope(sessionTopic, envelope)).jsonObject
        assertEquals(extendId, respJson["id"]!!.jsonPrimitive.long)
        assertNotNull(respJson["result"], "extend must still succeed (not error) for a past expiry")
        val after = s.manager.getSession(sessionTopic)!!
        assertEquals(originalExpiry, after.expiry, "a past proposed expiry must not shrink the session")
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000
        assertTrue(after.expiry >= now + 1, "expiry must remain in the future, got ${after.expiry} (now=$now)")
        s.manager.stop()
    }

    @Test
    fun malformedSessionRequestWithIdGetsParseError() = runTest {
        val s = settledSession(testScheduler)
        val sessionTopic = s.session.topic
        val requestId = 9901L
        // A valid JSON-RPC wc_sessionRequest frame, but the inner request omits "method".
        // With a valid id present, the wallet must answer a parse error, not drop it silently.
        val request = WcJsonRpc.formatJsonRpcRequest(
            "wc_sessionRequest",
            buildJsonObject {
                put("request", buildJsonObject {
                    put("params", buildJsonObject { put("message", "0x1234") })
                })
                put("chainId", "eip155:1")
            },
            requestId,
        )
        s.relay.pushTopic(sessionTopic, s.crypto.encodeEnvelope(sessionTopic, request.toString()), "m1")
        val publish = awaitValue {
            s.relay.frames("irn_publish").lastOrNull {
                it["params"]!!.jsonObject["topic"]!!.jsonPrimitive.content == sessionTopic
            }
        }
        val envelope = publish["params"]!!.jsonObject["message"]!!.jsonPrimitive.content
        val respJson = json.parseToJsonElement(s.crypto.decodeEnvelope(sessionTopic, envelope)).jsonObject
        assertEquals(requestId, respJson["id"]!!.jsonPrimitive.long)
        assertEquals(-32700, respJson["error"]!!.jsonObject["code"]!!.jsonPrimitive.long.toInt())
        s.manager.stop()
    }
}

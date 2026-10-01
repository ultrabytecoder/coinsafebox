package com.ultrabytecoder.coinsafebox.data.walletconnect

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WcRelayClientTest {

    private class InMemoryKeyChain : WcKeyChain {
        private val map = mutableMapOf<String, String>()
        override fun has(tag: String): Boolean = map.containsKey(tag)
        override fun get(tag: String): String? = map[tag]
        override fun set(tag: String, value: String) { map[tag] = value }
        override fun delete(tag: String) { map.remove(tag) }
        override fun loadAll(): Map<String, String> = map.toMap()
    }

    private fun newCrypto(): WcCrypto {
        val crypto = WcCrypto(InMemoryKeyChain())
        crypto.init()
        return crypto
    }

    private class FakeWcRelayTransport : WcRelayTransport {
        private val lock = Any()
        private val sentList = mutableListOf<JsonObject>()
        private val parse = Json { ignoreUnknownKeys = true }

        val sent: List<JsonObject> get() = synchronized(lock) { sentList.toList() }
        var lastUrl: String? = null
        var connectCount = 0
        private var textHandler: (suspend (String) -> Unit)? = null
        private var currentSession: WcRelaySession? = null

        fun recordUrl(url: String) {
            lastUrl = url
        }

        fun sentMethod(method: String): List<JsonObject> =
            sent.filter { it["method"]?.jsonPrimitive?.content == method }

        override suspend fun connect(onText: suspend (String) -> Unit): WcRelaySession {
            connectCount++
            textHandler = onText
            val session = object : WcRelaySession {
                override val closed = CompletableDeferred<Unit>()

                override suspend fun sendText(text: String) {
                    synchronized(lock) {
                        sentList.add(parse.parseToJsonElement(text).jsonObject)
                    }
                }

                override suspend fun close() {
                    closed.complete(Unit)
                }
            }
            currentSession = session
            return session
        }

        suspend fun pushInbound(text: String) {
            textHandler?.invoke(text)
        }

        fun dropConnection() {
            currentSession?.closed?.complete(Unit)
        }
    }

    private fun buildClient(
        crypto: WcCrypto,
        fake: FakeWcRelayTransport,
        scheduler: TestCoroutineScheduler,
    ): WcRelayClient = WcRelayClient(
        crypto = crypto,
        projectId = "test-project-id",
        transportFactory = { url -> fake.recordUrl(url); fake },
        reconnectDelayMillis = 10,
        requestTimeoutMillis = 100,
        dispatcher = UnconfinedTestDispatcher(scheduler),
    )

    private suspend fun waitFor(
        fake: FakeWcRelayTransport,
        condition: (List<JsonObject>) -> Boolean,
    ) {
        var waited = 0
        while (!condition(fake.sent)) {
            delay(1)
            waited++
            assertTrue(waited < 10_000, "timed out waiting for relay condition")
        }
    }

    /** Builds a relay JSON-RPC response echoing [id] with the given raw JSON [result]. */
    private fun echoResponse(id: JsonElement, result: String): String =
        """{"jsonrpc":"2.0","id":$id,"result":$result}"""

    private suspend fun pushSubscription(
        fake: FakeWcRelayTransport,
        pushId: String,
        topic: String,
        envelope: String,
    ) {
        fake.pushInbound(
            """{"jsonrpc":"2.0","id":$pushId,"topic":"$topic","method":"irn_subscription","params":{"id":"sub-1","data":{"topic":"$topic","message":"$envelope","publishedAt":1700000000}}}"""
        )
    }

    @Test
    fun connect_buildsRelayUrlWithVerifiableJwt() = runTest {
        val crypto = newCrypto()
        val fake = FakeWcRelayTransport()
        val client = buildClient(crypto, fake, testScheduler)
        client.connect()
        assertTrue(client.isConnected)
        val url = fake.lastUrl!!
        assertEquals("wss://relay.walletconnect.org", url.substringBefore('?'))
        val query = url.substringAfter('?').split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("wc-2%2Fcoinsafebox-1.0.0%2Fkmp", query["ua"])
        assertEquals("test-project-id", query["projectId"])
        assertEquals("true", query["useOnCloseEvent"])
        assertTrue(crypto.verifyJwt(query["auth"]!!), "auth JWT must verify")
        client.disconnect()
        assertFalse(client.isConnected)
    }

    @Test
    fun connectWithBlankProjectIdThrows() = runTest {
        val crypto = newCrypto()
        val fake = FakeWcRelayTransport()
        val client = WcRelayClient(
            crypto = crypto,
            projectId = "  ",
            transportFactory = { fake },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        assertFailsWith<WcProtocolException> { client.connect() }
        assertEquals(0, fake.connectCount)
    }

    @Test
    fun subscribe_sendsIrnsSubscribeAndReturnsStringSubId() = runTest {
        val crypto = newCrypto()
        val fake = FakeWcRelayTransport()
        val client = buildClient(crypto, fake, testScheduler)
        client.connect()
        val result = async { client.subscribe("topic-a") }
        waitFor(fake) { list -> list.any { it["method"]?.jsonPrimitive?.content == "irn_subscribe" } }
        val request = fake.sent.first { it["method"]?.jsonPrimitive?.content == "irn_subscribe" }
        assertEquals("topic-a", request["params"]!!.jsonObject["topic"]!!.jsonPrimitive.content)
        fake.pushInbound(echoResponse(request["id"]!!, "100"))
        assertEquals("100", result.await())
        client.disconnect()
    }

    @Test
    fun publish_sendsIrnpublishWithTtlPromptAndTag() = runTest {
        val crypto = newCrypto()
        val fake = FakeWcRelayTransport()
        val client = buildClient(crypto, fake, testScheduler)
        client.connect()
        val topic = crypto.setSymKey(crypto.generateRandomBytes32())
        val envelope = crypto.encodeEnvelope(topic, """{"hello":"world"}""")
        val job = launch { client.publish(topic, envelope, ttl = 9, prompt = true) }
        waitFor(fake) { list -> list.any { it["method"]?.jsonPrimitive?.content == "irn_publish" } }
        val request = fake.sent.first { it["method"]?.jsonPrimitive?.content == "irn_publish" }
        val params = request["params"]!!.jsonObject
        assertEquals(topic, params["topic"]!!.jsonPrimitive.content)
        assertEquals(envelope, params["message"]!!.jsonPrimitive.content)
        assertEquals(9, params["ttl"]!!.jsonPrimitive.int)
        assertEquals(true, params["prompt"]!!.jsonPrimitive.boolean)
        assertEquals(0, params["tag"]!!.jsonPrimitive.int)
        fake.pushInbound(echoResponse(request["id"]!!, "55"))
        job.join()
        client.disconnect()
    }

    @Test
    fun unsubscribe_sendsStoredStringSubId() = runTest {
        val crypto = newCrypto()
        val fake = FakeWcRelayTransport()
        val client = buildClient(crypto, fake, testScheduler)
        client.connect()
        val subResult = async { client.subscribe("topic-a") }
        waitFor(fake) { list -> list.any { it["method"]?.jsonPrimitive?.content == "irn_subscribe" } }
        val subRequest = fake.sent.first { it["method"]?.jsonPrimitive?.content == "irn_subscribe" }
        fake.pushInbound(echoResponse(subRequest["id"]!!, "100"))
        assertEquals("100", subResult.await())

        val unsubResult = async { client.unsubscribe("topic-a") }
        waitFor(fake) { list -> list.any { it["method"]?.jsonPrimitive?.content == "irn_unsubscribe" } }
        val unsub = fake.sent.first { it["method"]?.jsonPrimitive?.content == "irn_unsubscribe" }
        val params = unsub["params"]!!.jsonObject
        assertEquals("100", params["id"]!!.jsonPrimitive.content)
        assertEquals("topic-a", params["topic"]!!.jsonPrimitive.content)
        fake.pushInbound(echoResponse(unsub["id"]!!, "true"))
        unsubResult.await()
        client.disconnect()
    }

    @Test
    fun relayErrorResponseFailsTheMatchingRequest() = runTest {
        val crypto = newCrypto()
        val fake = FakeWcRelayTransport()
        val client = buildClient(crypto, fake, testScheduler)
        client.connect()
        var thrown: Throwable? = null
        val job = launch {
            try { client.subscribe("topic-a") } catch (e: Throwable) { thrown = e }
        }
        waitFor(fake) { list -> list.any { it["method"]?.jsonPrimitive?.content == "irn_subscribe" } }
        val request = fake.sent.first { it["method"]?.jsonPrimitive?.content == "irn_subscribe" }
        fake.pushInbound(
            """{"jsonrpc":"2.0","id":${request["id"]!!},"error":{"code":300,"message":"topic not found"}}"""
        )
        job.join()
        val ex = thrown as? WcProtocolException ?: error("expected WcProtocolException, got: $thrown")
        assertTrue(ex.message!!.contains("300"))
        assertTrue(ex.message!!.contains("topic not found"))
        client.disconnect()
    }

    @Test
    fun subscriptionNotificationAcksDecodesAndInvokesHandler() = runTest {
        val crypto = newCrypto()
        val fake = FakeWcRelayTransport()
        val client = buildClient(crypto, fake, testScheduler)
        client.connect()
        val topic = crypto.setSymKey(crypto.generateRandomBytes32())
        val payloadJson = """{"jsonrpc":"2.0","id":9,"method":"wc_sessionRequest","params":{"request":{"method":"personal_sign"}}}"""
        val envelope = crypto.encodeEnvelope(topic, payloadJson)
        var received: Pair<String, String>? = null
        client.setOnMessage { t, p -> received = t to p }
        pushSubscription(fake, pushId = "77", topic = topic, envelope = envelope)

        assertEquals(topic to payloadJson, received)
        val ack = fake.sent.firstOrNull {
            it["method"] == null && it["id"]?.jsonPrimitive?.content == "77"
        }
        assertNotNull(ack)
        assertEquals(true, ack!!["result"]!!.jsonPrimitive.boolean)
        client.disconnect()
    }

    @Test
    fun duplicateMessagesAreDedupedByHash() = runTest {
        val crypto = newCrypto()
        val fake = FakeWcRelayTransport()
        val client = buildClient(crypto, fake, testScheduler)
        client.connect()
        val topic = crypto.setSymKey(crypto.generateRandomBytes32())
        val envelope = crypto.encodeEnvelope(topic, "{}")
        var invocations = 0
        client.setOnMessage { _, _ -> invocations++ }
        pushSubscription(fake, pushId = "77", topic = topic, envelope = envelope)
        pushSubscription(fake, pushId = "78", topic = topic, envelope = envelope)
        assertEquals(1, invocations)
        client.disconnect()
    }

    @Test
    fun malformedAndUnknownFramesAreIgnored() = runTest {
        val crypto = newCrypto()
        val fake = FakeWcRelayTransport()
        val client = buildClient(crypto, fake, testScheduler)
        client.connect()
        fake.pushInbound("this is not json at all")
        fake.pushInbound("""{"jsonrpc":"2.0","id":5,"topic":"t","method":"some_other_method","params":{}}""")
        fake.pushInbound("""{"jsonrpc":"2.0","id":999,"result":true}""")
        assertTrue(client.isConnected)
        val result = async { client.subscribe("topic-a") }
        waitFor(fake) { list -> list.any { it["method"]?.jsonPrimitive?.content == "irn_subscribe" } }
        val request = fake.sent.first { it["method"]?.jsonPrimitive?.content == "irn_subscribe" }
        fake.pushInbound(echoResponse(request["id"]!!, "100"))
        assertEquals("100", result.await())
        client.disconnect()
    }

    @Test
    fun droppedConnectionFailsInflightAndReconnectsWithResubscribe() = runTest {
        val crypto = newCrypto()
        val fake = FakeWcRelayTransport()
        val client = buildClient(crypto, fake, testScheduler)
        client.connect()

        val subResult = async { client.subscribe("topic-a") }
        waitFor(fake) { list -> list.any { it["method"]?.jsonPrimitive?.content == "irn_subscribe" } }
        val firstSub = fake.sent.first { it["method"]?.jsonPrimitive?.content == "irn_subscribe" }
        fake.pushInbound(echoResponse(firstSub["id"]!!, "100"))
        assertEquals("100", subResult.await())

        val topic = crypto.setSymKey(crypto.generateRandomBytes32())
        val envelope = crypto.encodeEnvelope(topic, "{}")
        var thrown: Throwable? = null
        val inFlightJob = launch {
            try { client.publish(topic, envelope) } catch (e: Throwable) { thrown = e }
        }
        waitFor(fake) { list -> list.any { it["method"]?.jsonPrimitive?.content == "irn_publish" } }
        fake.dropConnection()
        inFlightJob.join()
        assertTrue(thrown is WcProtocolException, "in-flight request must fail after drop, got: $thrown")

        waitFor(fake) { list -> list.count { it["method"]?.jsonPrimitive?.content == "irn_subscribe" } >= 2 }
        assertEquals(2, fake.connectCount)
        assertTrue(client.isConnected)
        val resub = fake.sent.last { it["method"]?.jsonPrimitive?.content == "irn_subscribe" }
        assertEquals("topic-a", resub["params"]!!.jsonObject["topic"]!!.jsonPrimitive.content)
        fake.pushInbound(echoResponse(resub["id"]!!, "101"))
        client.disconnect()
    }

    @Test
    fun disconnectPreventsReconnect() = runTest {
        val crypto = newCrypto()
        val fake = FakeWcRelayTransport()
        val client = buildClient(crypto, fake, testScheduler)
        client.connect()
        assertEquals(1, fake.connectCount)
        client.disconnect()
        delay(100)
        assertEquals(1, fake.connectCount)
        assertFalse(client.isConnected)
    }

    @Test
    fun requestsWhileDisconnectedFailFast() = runTest {
        val crypto = newCrypto()
        val fake = FakeWcRelayTransport()
        val client = buildClient(crypto, fake, testScheduler)
        assertFailsWith<WcProtocolException> { client.publish("t", "m") }
        assertFailsWith<WcProtocolException> { client.subscribe("t") }
        assertFailsWith<WcProtocolException> { client.unsubscribe("t") }
        assertEquals(0, fake.connectCount)
    }

    @Test
    fun requestWithoutResponseTimesOutAndDoesNotLeakPending() = runTest {
        val crypto = newCrypto()
        val fake = FakeWcRelayTransport()
        val client = buildClient(crypto, fake, testScheduler)
        client.connect()
        val ex = assertFailsWith<WcProtocolException> { client.subscribe("topic-a") }
        assertTrue(ex.message!!.contains("timed out"))
        val timedOutId = fake.sent.first()["id"]!!
        fake.pushInbound(echoResponse(timedOutId, "100"))

        val result = async { client.subscribe("topic-b") }
        waitFor(fake) { list ->
            list.any {
                it["method"]?.jsonPrimitive?.content == "irn_subscribe" &&
                    it["params"]!!.jsonObject["topic"]!!.jsonPrimitive.content == "topic-b"
            }
        }
        val sub = fake.sent.last { it["method"]?.jsonPrimitive?.content == "irn_subscribe" }
        fake.pushInbound(echoResponse(sub["id"]!!, "200"))
        assertEquals("200", result.await())
        client.disconnect()
    }
}

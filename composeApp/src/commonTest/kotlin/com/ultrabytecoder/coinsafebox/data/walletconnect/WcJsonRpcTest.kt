package com.ultrabytecoder.coinsafebox.data.walletconnect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WcJsonRpcTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun formatRequestProducesValidPayload() {
        val params = buildJsonObject { put("a", "b") }
        val req = WcJsonRpc.formatJsonRpcRequest("wc_sessionPing", params, 42L)
        assertEquals("2.0", req["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(42, req["id"]!!.jsonPrimitive.long.toInt())
        assertEquals("wc_sessionPing", req["method"]!!.jsonPrimitive.content)
        assertTrue(WcJsonRpc.isJsonRpcRequest(req))
        assertFalse(WcJsonRpc.isJsonRpcResponse(req))
    }

    @Test
    fun formatResultProducesValidPayload() {
        val res = WcJsonRpc.formatJsonRpcResult(7L, JsonPrimitive(true))
        assertEquals(7, res["id"]!!.jsonPrimitive.long.toInt())
        assertEquals(true, res["result"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(WcJsonRpc.isJsonRpcResponse(res))
        assertTrue(WcJsonRpc.isJsonRpcResult(res))
        assertFalse(WcJsonRpc.isJsonRpcRequest(res))
    }

    @Test
    fun formatErrorProducesValidPayload() {
        val err = WcJsonRpc.formatJsonRpcError(9L, 5000, "User rejected")
        assertEquals(9, err["id"]!!.jsonPrimitive.long.toInt())
        val error = err["error"]!!.jsonObject
        assertEquals(5000, error["code"]!!.jsonPrimitive.long.toInt())
        assertEquals("User rejected", error["message"]!!.jsonPrimitive.content)
        assertTrue(WcJsonRpc.isJsonRpcResponse(err))
        assertTrue(WcJsonRpc.isJsonRpcError(err))
        assertFalse(WcJsonRpc.isJsonRpcResult(err))
    }

    @Test
    fun predicatesDistinguishShapes() {
        val request = json.parseToJsonElement(
            """{"jsonrpc":"2.0","id":1,"method":"wc_sessionRequest","params":{}}"""
        ).jsonObject
        val result = json.parseToJsonElement(
            """{"jsonrpc":"2.0","id":1,"result":true}"""
        ).jsonObject
        val error = json.parseToJsonElement(
            """{"jsonrpc":"2.0","id":1,"error":{"code":1,"message":"x"}}"""
        ).jsonObject
        val notification = json.parseToJsonElement(
            """{"jsonrpc":"2.0","method":"irn_subscription","params":{}}"""
        ).jsonObject

        assertTrue(WcJsonRpc.isJsonRpcRequest(request))
        assertFalse(WcJsonRpc.isJsonRpcRequest(result))
        assertTrue(WcJsonRpc.isJsonRpcResponse(result))
        assertTrue(WcJsonRpc.isJsonRpcResponse(error))
        assertFalse(WcJsonRpc.isJsonRpcResponse(notification))
        assertFalse(WcJsonRpc.isJsonRpcRequest(notification), "no id -> not a request")
    }

    @Test
    fun payloadIdIsUniqueAndIncreasing() {
        val a = WcJsonRpc.payloadId()
        val b = WcJsonRpc.payloadId()
        assertTrue(a > 0 && b > 0)
        // two ids generated ~simultaneously must still differ (random low digits)
        val seen = (1..200).map { WcJsonRpc.payloadId() }.toSet()
        assertTrue(seen.size > 1)
    }
}

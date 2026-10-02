package com.ultrabytecoder.coinsafebox.data.walletconnect

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Clock

/**
 * WalletConnect JSON-RPC 2.0 payload helpers (client-client messages inside envelopes).
 * Distinct from the relay-level JSON-RPC handled by [WcRelayClient].
 */
object WcJsonRpc {

    fun formatJsonRpcRequest(method: String, params: JsonObject, id: Long): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

    fun formatJsonRpcResult(id: Long, result: JsonElement): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

    fun formatJsonRpcError(id: Long, code: Int, message: String): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }

    fun isJsonRpcRequest(payload: JsonObject): Boolean =
        payload.containsKey("method") && payload.containsKey("id")

    fun isJsonRpcResponse(payload: JsonObject): Boolean =
        payload.containsKey("id") && (payload.containsKey("result") || payload.containsKey("error"))

    fun isJsonRpcResult(payload: JsonObject): Boolean =
        payload.containsKey("result") && !payload.containsKey("error")

    fun isJsonRpcError(payload: JsonObject): Boolean =
        payload.containsKey("error")

    /**
     * Generates a unique numeric id for client-client JSON-RPC messages.
     * Mirrors the SDK's `payloadId()` — millisecond epoch * 1000 + random(0..999).
     */
    fun payloadId(): Long = Clock.System.now().toEpochMilliseconds() * 1000L + Random.nextLong(0, 1000)
}

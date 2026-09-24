package com.ultrabytecoder.coinsafebox.providers.ton

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class TonBaseParseWalletVersionTest {

    @Test
    fun emptyParams_defaultsToV3R2() {
        val version = TonBase.parseWalletVersion(JsonObject(emptyMap()))
        assertEquals(TonWalletVersion.V3R2, version)
    }

    @Test
    fun explicitV3R2() {
        val params = JsonObject(mapOf("walletVersion" to JsonPrimitive("V3R2")))
        val version = TonBase.parseWalletVersion(params)
        assertEquals(TonWalletVersion.V3R2, version)
    }

    @Test
    fun explicitV4R2() {
        val params = JsonObject(mapOf("walletVersion" to JsonPrimitive("V4R2")))
        val version = TonBase.parseWalletVersion(params)
        assertEquals(TonWalletVersion.V4R2, version)
    }

    @Test
    fun explicitV3R1() {
        val params = JsonObject(mapOf("walletVersion" to JsonPrimitive("V3R1")))
        val version = TonBase.parseWalletVersion(params)
        assertEquals(TonWalletVersion.V3R1, version)
    }

    @Test
    fun explicitV4R1() {
        val params = JsonObject(mapOf("walletVersion" to JsonPrimitive("V4R1")))
        val version = TonBase.parseWalletVersion(params)
        assertEquals(TonWalletVersion.V4R1, version)
    }

    @Test
    fun unknownVersion_defaultsToV3R2() {
        val params = JsonObject(mapOf("walletVersion" to JsonPrimitive("V999")))
        val version = TonBase.parseWalletVersion(params)
        assertEquals(TonWalletVersion.V3R2, version)
    }

    @Test
    fun otherParamsIgnored() {
        val params = JsonObject(mapOf(
            "someOtherKey" to JsonPrimitive("value"),
            "walletVersion" to JsonPrimitive("V4R2")
        ))
        val version = TonBase.parseWalletVersion(params)
        assertEquals(TonWalletVersion.V4R2, version)
    }
}

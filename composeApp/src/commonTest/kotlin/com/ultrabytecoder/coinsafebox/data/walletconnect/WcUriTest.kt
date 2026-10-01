package com.ultrabytecoder.coinsafebox.data.walletconnect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WcUriTest {

    private val symKey = "587d5484ce2a2a6ee3ba1962fdd7e8588e06200c46823bd18fbd67def96ad303"
    private val topic = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"

    @Test
    fun parsesStandardUri() {
        val uri = "wc:$topic@2?relay-protocol=irn&symKey=$symKey"
        val parsed = WcUri.parseUri(uri)
        assertEquals("", parsed.protocol)
        assertEquals(topic, parsed.topic)
        assertEquals(2, parsed.version)
        assertEquals(symKey, parsed.symKey)
        assertEquals("irn", parsed.relayProtocol)
        assertNull(parsed.methods)
        assertNull(parsed.expiryTimestamp)
    }

    @Test
    fun parsesUriWithMethodsAndExpiry() {
        val uri = "wc:$topic@2?relay-protocol=irn&symKey=$symKey&methods=wc_sessionPropose,wc_sessionRequest&expiryTimestamp=1700000000"
        val parsed = WcUri.parseUri(uri)
        assertEquals(listOf("wc_sessionPropose", "wc_sessionRequest"), parsed.methods)
        assertEquals(1700000000L, parsed.expiryTimestamp)
    }

    @Test
    fun parsesBase64WrappedUri() {
        val inner = "wc:$topic@2?relay-protocol=irn&symKey=$symKey"
        val wrapped = WcEncoding.base64Encode(inner.encodeToByteArray())
        val parsed = WcUri.parseUri(wrapped)
        assertEquals(topic, parsed.topic)
        assertEquals(symKey, parsed.symKey)
    }

    @Test
    fun stripsDoubleSlashSchemePrefix() {
        val uri = "wc://$topic@2?relay-protocol=irn&symKey=$symKey"
        val parsed = WcUri.parseUri(uri)
        assertEquals(topic, parsed.topic)
    }

    @Test
    fun stripsLeadingDoubleSlashInTopic() {
        val uri = "wc://$topic@2?relay-protocol=irn&symKey=$symKey"
        val parsed = WcUri.parseUri(uri)
        assertTrue(!parsed.topic.startsWith("//"))
    }

    @Test
    fun missingSymKeyThrows() {
        val uri = "wc:$topic@2?relay-protocol=irn"
        assertFailsWith<WcProtocolException> { WcUri.parseUri(uri) }
    }

    @Test
    fun missingRelayProtocolThrows() {
        val uri = "wc:$topic@2?symKey=$symKey"
        assertFailsWith<WcProtocolException> { WcUri.parseUri(uri) }
    }

    @Test
    fun malformedUriThrows() {
        assertFailsWith<WcProtocolException> { WcUri.parseUri("not-a-uri") }
    }
}

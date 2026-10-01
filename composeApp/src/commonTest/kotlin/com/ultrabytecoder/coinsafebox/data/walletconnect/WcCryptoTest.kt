package com.ultrabytecoder.coinsafebox.data.walletconnect

import io.github.andreypfau.curve25519.ed25519.Ed25519
import io.github.andreypfau.curve25519.x25519.X25519
import org.kotlincrypto.random.CryptoRand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WcCryptoTest {

    private class InMemoryKeyChain : WcKeyChain {
        private val map = mutableMapOf<String, String>()
        override fun has(tag: String): Boolean = map.containsKey(tag)
        override fun get(tag: String): String? = map[tag]
        override fun set(tag: String, value: String) { map[tag] = value }
        override fun delete(tag: String) { map.remove(tag) }
        override fun loadAll(): Map<String, String> = map.toMap()
    }

    private fun newCrypto(): WcCrypto {
        val keychain = InMemoryKeyChain()
        val crypto = WcCrypto(keychain)
        crypto.init()
        return crypto
    }

    @Test
    fun x25519_publicKey_alice() {
        val priv = WcEncoding.hexDecode("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val pub = X25519.x25519(priv)
        assertEquals(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            WcEncoding.hexEncode(pub)
        )
    }

    @Test
    fun x25519_publicKey_bob() {
        val priv = WcEncoding.hexDecode("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val pub = X25519.x25519(priv)
        assertEquals(
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f",
            WcEncoding.hexEncode(pub)
        )
    }

    @Test
    fun x25519_ecdh() {
        val alicePriv = WcEncoding.hexDecode("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPub = WcEncoding.hexDecode("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = X25519.x25519(alicePriv, bobPub.copyOf())
        assertEquals(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            WcEncoding.hexEncode(shared)
        )
    }

    @Test
    fun deriveSymKey_matches_reference() {
        val crypto = newCrypto()
        val alicePriv = WcEncoding.hexDecode("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPub = WcEncoding.hexDecode("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val symKey = crypto.deriveSymKey(alicePriv, bobPub)
        assertEquals(
            "ea1d8a20f476d1e1ec952ca42708b8f7161ce7c81eadf97e520e2b40333decd5",
            WcEncoding.hexEncode(symKey)
        )
    }

    @Test
    fun hashKey_matches_reference() {
        val crypto = newCrypto()
        val topic = crypto.hashKey("ea1d8a20f476d1e1ec952ca42708b8f7161ce7c81eadf97e520e2b40333decd5")
        assertEquals(
            "1ca1d70db64cab0f93de5934e27f7114e8e9fd7dd3c7145d81ce7f2dd2cd05c8",
            topic
        )
    }

    @Test
    fun hkdf_sha256_rfc5869_tc1() {
        val crypto = newCrypto()
        val ikm = ByteArray(22) { it.toByte() }
        val salt = ByteArray(13) { (it + 1).toByte() }
        val info = ByteArray(8) { (it + 0x0d).toByte() }
        val okm = crypto.hkdfSha256(ikm, salt, info, 42)
        assertEquals(
            "9433474358dfd408ddfd8588feb14fd0a91b5b5ab218d0806bde90f5232757fecb2e09c1151a59954c2a",
            WcEncoding.hexEncode(okm)
        )
    }

    @Test
    fun ed25519_keyFromSeed_publicKey() {
        val seed = WcEncoding.hexDecode("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val key = Ed25519.keyFromSeed(seed)
        val publicKey = key.publicKey().toByteArray()
        assertEquals(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            WcEncoding.hexEncode(publicKey)
        )
    }

    @Test
    fun ed25519_sign_empty() {
        val seed = WcEncoding.hexDecode("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val key = Ed25519.keyFromSeed(seed)
        val sig = key.sign(ByteArray(0))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            WcEncoding.hexEncode(sig)
        )
    }

    @Test
    fun chacha20poly1305_vector1() {
        val crypto = newCrypto()
        val key = ByteArray(32)
        val nonce = WcEncoding.hexDecode("000000000000014200000000")
        val plaintext = ByteArray(64) { it.toByte() }
        val sealed = crypto.chachaSeal(key, nonce, plaintext)
        assertEquals(
            "8e234c89025ab8a0b2351a9b53cfded166b45afdbdb07371bd03a559663a69d254a1f04576de7172f42ef206df1bc3d990232334504c21447271e03fb520a99e5d2f00934ef0ed526d55cfe575816df3",
            WcEncoding.hexEncode(sealed)
        )
    }

    @Test
    fun chacha20poly1305_vector2() {
        val crypto = newCrypto()
        val key = WcEncoding.hexDecode("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = WcEncoding.hexDecode("070000004a0009004a000900")
        val plaintext = ByteArray(80) { it.toByte() }
        val sealed = crypto.chachaSeal(key, nonce, plaintext)
        assertEquals(
            "6f5aa0c02c0e7668f7494c8b38da0b4ac050ec2f462a5a67efaa0aeecce43f4f883d1351076fb14bf2ba40f77fe695990af93efa80e30a57c55b4e3ba8136e27cbac0b121be296506013e290f6bc3305a73ca8cf8caaa9104af3eda9246e1e69",
            WcEncoding.hexEncode(sealed)
        )
    }

    @Test
    fun chacha20poly1305_roundtrip() {
        val crypto = newCrypto()
        val key = CryptoRand.Default.nextBytes(ByteArray(32))
        val nonce = CryptoRand.Default.nextBytes(ByteArray(12))
        val plaintext = "Hello, WalletConnect!".encodeToByteArray()
        val sealed = crypto.chachaSeal(key, nonce, plaintext)
        val opened = crypto.chachaOpen(key, nonce, sealed)
        assertEquals(String(plaintext), String(opened))
    }

    @Test
    fun envelope_roundtrip() {
        val crypto = newCrypto()
        val symKey = crypto.generateRandomBytes32()
        val topic = crypto.setSymKey(symKey)
        val payload = """{"jsonrpc":"2.0","id":1,"method":"test"}"""
        val encoded = crypto.encodeEnvelope(topic, payload)
        val decoded = crypto.decodeEnvelope(topic, encoded)
        assertEquals(payload, decoded)
    }

    @Test
    fun envelope_type0_layout() {
        val crypto = newCrypto()
        val symKey = crypto.generateRandomBytes32()
        val topic = crypto.setSymKey(symKey)
        val payload = """{"test":true}"""
        val encoded = crypto.encodeEnvelope(topic, payload)
        val bytes = WcEncoding.base64Decode(encoded)
        assertEquals(0, bytes[0].toInt(), "First byte must be 0x00 (type 0)")
        assertEquals(12, bytes.copyOfRange(1, 13).size, "IV must be 12 bytes")
        val expectedSealedLen = payload.encodeToByteArray().size + 16
        assertEquals(expectedSealedLen, bytes.size - 13, "Sealed section must be ct(plaintext_len) + tag(16)")
    }

    @Test
    fun envelope_type2_decode() {
        val crypto = newCrypto()
        val plaintext = """{"hello":"world"}"""
        val type2Bytes = ByteArray(1 + plaintext.encodeToByteArray().size)
        type2Bytes[0] = 0x02
        plaintext.encodeToByteArray().copyInto(type2Bytes, 1)
        val encoded = WcEncoding.base64Encode(type2Bytes)
        val decoded = crypto.decodeEnvelope("dummy-topic", encoded)
        assertEquals(plaintext, decoded)
    }

    @Test
    fun jwt_relayAuth_signAndVerify() {
        val crypto = newCrypto()
        val relayUrl = "wss://relay.walletconnect.com"
        val jwt = crypto.relayAuthJwt(relayUrl)
        assertTrue(crypto.verifyJwt(jwt), "JWT must verify as true")
    }

    @Test
    fun jwt_tampered_fails() {
        val crypto = newCrypto()
        val relayUrl = "wss://relay.walletconnect.com"
        val jwt = crypto.relayAuthJwt(relayUrl)
        val parts = jwt.split(".").toMutableList()
        val payloadBytes = WcEncoding.base64UrlDecode(parts[1])
        payloadBytes[0] = (payloadBytes[0].toInt() xor 1).toByte()
        parts[1] = WcEncoding.base64UrlEncode(payloadBytes)
        val tampered = parts.joinToString(".")
        assertFalse(crypto.verifyJwt(tampered), "Tampered JWT must fail verification")
    }

    @Test
    fun jwt_claims_areCorrect() {
        val crypto = newCrypto()
        val relayUrl = "wss://relay.walletconnect.com"
        val jwt = crypto.relayAuthJwt(relayUrl)
        val parts = jwt.split(".")
        assertEquals(3, parts.size)
        val payloadJson = WcEncoding.base64UrlDecode(parts[1]).decodeToString()
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(payloadJson)
            .let { it as kotlinx.serialization.json.JsonObject }
        val iss = payload["iss"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        assertNotNull(iss, "iss claim must be present")
        assertTrue(iss.startsWith("did:key:z6Mk"), "iss must start with did:key:z6Mk")
        val aud = payload["aud"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        assertEquals(relayUrl, aud, "aud must equal relay URL")
        val iat = payload["iat"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }?.toLong()
        val exp = payload["exp"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }?.toLong()
        assertNotNull(iat)
        assertNotNull(exp)
        assertEquals(604800L, exp - iat, "exp - iat must be 604800 seconds")
    }

    @Test
    fun didKey_forRfc8032_seed_starts_with_z6Mk() {
        val crypto = newCrypto()
        val seed = WcEncoding.hexDecode("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val publicKey = Ed25519.keyFromSeed(seed).publicKey().toByteArray()
        val iss = crypto.encodeIss(publicKey)
        assertTrue(iss.startsWith("did:key:z6Mk"), "did:key must start with did:key:z6Mk")
    }

    @Test
    fun didKey_roundtrip() {
        val crypto = newCrypto()
        val seed = WcEncoding.hexDecode("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val publicKey = Ed25519.keyFromSeed(seed).publicKey().toByteArray()
        val iss = crypto.encodeIss(publicKey)
        val decoded = crypto.decodeIss(iss)
        assertTrue(publicKey.contentEquals(decoded), "decodeIss(encodeIss(pub)) must round-trip")
    }

    @Test
    fun base58btc_roundtrip() {
        val testBytes = listOf(
            byteArrayOf(0xED.toByte(), 0x01) + ByteArray(32) { it.toByte() },
            ByteArray(32) { (it * 7).toByte() },
            byteArrayOf(0x00, 0x00, 0x01, 0x02, 0x03),
            ByteArray(0),
            ByteArray(1) { 0x42 }
        )
        for (bytes in testBytes) {
            val encoded = WcEncoding.base58btcEncode(bytes)
            val decoded = WcEncoding.base58btcDecode(encoded)
            assertTrue(bytes.contentEquals(decoded), "base58btc round-trip failed for ${WcEncoding.hexEncode(bytes)}")
        }
    }

    @Test
    fun base58btc_known_vector() {
        val multicodec = byteArrayOf(0xED.toByte(), 0x01)
        val encoded = WcEncoding.base58btcEncode(multicodec)
        assertEquals("K36", encoded)
        val decoded = WcEncoding.base58btcDecode("K36")
        assertTrue(multicodec.contentEquals(decoded))
    }

    @Test
    fun base64Url_roundtrip_noPadding() {
        val testBytes = listOf(
            ByteArray(0),
            ByteArray(1) { 0x42 },
            byteArrayOf(0x42, 0x43),
            byteArrayOf(0x42, 0x43, 0x44),
            ByteArray(32) { it.toByte() },
            ByteArray(64) { (it * 3).toByte() }
        )
        for (bytes in testBytes) {
            val encoded = WcEncoding.base64UrlEncode(bytes)
            assertFalse(encoded.contains("="), "base64UrlEncode must not produce padding")
            val decoded = WcEncoding.base64UrlDecode(encoded)
            assertTrue(bytes.contentEquals(decoded), "base64Url round-trip failed for size ${bytes.size}")
        }
    }

    @Test
    fun base64Url_decode_tolerates_padding() {
        val data = "Hello, WC!".encodeToByteArray()
        val encodedNoPad = WcEncoding.base64UrlEncode(data)
        val encodedWithPad = encodedNoPad + "=".repeat((4 - encodedNoPad.length % 4) % 4)
        val decoded1 = WcEncoding.base64UrlDecode(encodedNoPad)
        val decoded2 = WcEncoding.base64UrlDecode(encodedWithPad)
        assertTrue(data.contentEquals(decoded1))
        assertTrue(data.contentEquals(decoded2))
    }

    @Test
    fun clientId_is_stable() {
        val crypto = newCrypto()
        val id1 = crypto.clientId
        val id2 = crypto.clientId
        assertEquals(id1, id2, "clientId must be stable across accesses")
        assertTrue(id1.startsWith("did:key:z6Mk"))
    }

    @Test
    fun setSymKey_with_overrideTopic() {
        val crypto = newCrypto()
        val symKey = crypto.generateRandomBytes32()
        val customTopic = "abc123customtopic"
        val returnedTopic = crypto.setSymKey(symKey, customTopic)
        assertEquals(customTopic, returnedTopic)
        val retrieved = crypto.getSymKey(customTopic)
        assertNotNull(retrieved)
        assertTrue(symKey.contentEquals(retrieved))
    }

    @Test
    fun generateX25519KeyPair_stores_and_canDerive() {
        val crypto = newCrypto()
        val pair = crypto.generateX25519KeyPair()
        val pubHex = WcEncoding.hexEncode(pair.publicKey)
        assertTrue(crypto.hasKey(pubHex), "Private key must be stored under public key hex tag")
        val pair2 = crypto.generateX25519KeyPair()
        val shared1 = crypto.deriveSymKey(pair.privateKey, pair2.publicKey)
        val shared2 = crypto.deriveSymKey(pair2.privateKey, pair.publicKey)
        assertTrue(shared1.contentEquals(shared2), "ECDH must be symmetric")
    }
}

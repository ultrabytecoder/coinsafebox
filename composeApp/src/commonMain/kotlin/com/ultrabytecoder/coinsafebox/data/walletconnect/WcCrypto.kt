package com.ultrabytecoder.coinsafebox.data.walletconnect

import com.ultrabytecoder.coinsafebox.data.SettingsStorage
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import io.github.andreypfau.curve25519.ed25519.Ed25519
import io.github.andreypfau.curve25519.ed25519.Ed25519PublicKey
import io.github.andreypfau.curve25519.x25519.X25519
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kotlincrypto.random.CryptoRand

class WcProtocolException(message: String) : Exception(message)

data class WcX25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

class WcCrypto internal constructor(
    private val keychain: WcKeyChain
) {
    constructor(storage: SettingsStorage) : this(SettingsWcKeyChain(storage))

    private val cryptoProvider = CryptographyProvider.Default
    private val sha256Hasher = cryptoProvider.get(SHA256).hasher()

    val clientId: String by lazy {
        val seed = clientSeedBytes()
        val publicKey = Ed25519.keyFromSeed(seed).publicKey().toByteArray()
        encodeIss(publicKey)
    }

    fun init() {
        if (!keychain.has(CLIENT_SEED_TAG)) {
            val seed = CryptoRand.Default.nextBytes(ByteArray(32))
            keychain.set(CLIENT_SEED_TAG, WcEncoding.hexEncode(seed))
        }
    }

    private fun clientSeedBytes(): ByteArray {
        val hex = keychain.get(CLIENT_SEED_TAG)
            ?: throw IllegalStateException("WcCrypto not initialized; call init() first")
        return WcEncoding.hexDecode(hex)
    }

    fun relayAuthJwt(relayUrl: String): String {
        val sub = WcEncoding.hexEncode(CryptoRand.Default.nextBytes(ByteArray(32)))
        return signJwt(sub, relayUrl, 604800L)
    }

    fun signJwt(sub: String, aud: String, ttlSeconds: Long): String {
        val seed = clientSeedBytes()
        val edKey = Ed25519.keyFromSeed(seed)
        val publicKey = edKey.publicKey().toByteArray()
        val iss = encodeIss(publicKey)
        val iat = Clock.System.now().toEpochMilliseconds() / 1000L
        val exp = iat + ttlSeconds

        val headerJson = """{"alg":"EdDSA","typ":"JWT"}"""
        val payloadJson = """{"iss":"$iss","sub":"$sub","aud":"$aud","iat":$iat,"exp":$exp}"""

        val headerB64 = WcEncoding.base64UrlEncode(headerJson.encodeToByteArray())
        val payloadB64 = WcEncoding.base64UrlEncode(payloadJson.encodeToByteArray())
        val signingInput = "$headerB64.$payloadB64".encodeToByteArray()
        val signature = edKey.sign(signingInput)
        val sigB64 = WcEncoding.base64UrlEncode(signature)

        return "$headerB64.$payloadB64.$sigB64"
    }

    fun verifyJwt(jwt: String): Boolean {
        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) return false

            val headerJson = WcEncoding.base64UrlDecode(parts[0]).decodeToString()
            val payloadJson = WcEncoding.base64UrlDecode(parts[1]).decodeToString()
            val signature = WcEncoding.base64UrlDecode(parts[2])

            val header = Json.parseToJsonElement(headerJson).jsonObject
            if (header["alg"]?.jsonPrimitive?.contentOrNull != "EdDSA") return false
            if (header["typ"]?.jsonPrimitive?.contentOrNull != "JWT") return false

            val payload = Json.parseToJsonElement(payloadJson).jsonObject
            val iss = payload["iss"]?.jsonPrimitive?.contentOrNull ?: return false
            val publicKey = decodeIss(iss)

            val signingInput = "${parts[0]}.${parts[1]}".encodeToByteArray()
            Ed25519PublicKey(publicKey).verify(signingInput, signature)
        } catch (e: Exception) {
            false
        }
    }

    fun generateX25519KeyPair(): WcX25519KeyPair {
        val privateKey = CryptoRand.Default.nextBytes(ByteArray(32))
        val publicKey = X25519.x25519(privateKey)
        keychain.set(WcEncoding.hexEncode(publicKey), WcEncoding.hexEncode(privateKey))
        return WcX25519KeyPair(privateKey, publicKey)
    }

    fun deriveSymKey(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val shared = X25519.x25519(privateKey, peerPublicKey.copyOf())
        return hkdfSha256(shared, ByteArray(0), ByteArray(0), 32)
    }

    fun sessionTopicFor(selfPublicKeyHex: String, peerPublicKeyHex: String): String {
        val privateKeyHex = keychain.get(selfPublicKeyHex)
            ?: throw WcProtocolException("No private key stored for public key: $selfPublicKeyHex")
        val privateKey = WcEncoding.hexDecode(privateKeyHex)
        val peerPublicKey = WcEncoding.hexDecode(peerPublicKeyHex)
        val symKey = deriveSymKey(privateKey, peerPublicKey)
        return setSymKey(symKey)
    }

    fun hashKey(symKeyHex: String): String {
        return WcEncoding.hexEncode(sha256(WcEncoding.hexDecode(symKeyHex)))
    }

    fun hashMessage(message: String): String {
        return WcEncoding.hexEncode(sha256(message.encodeToByteArray()))
    }

    fun setSymKey(symKey: ByteArray, overrideTopic: String? = null): String {
        val symKeyHex = WcEncoding.hexEncode(symKey)
        val topic = overrideTopic ?: hashKey(symKeyHex)
        keychain.set(topic, symKeyHex)
        return topic
    }

    fun getSymKey(topic: String): ByteArray? {
        return keychain.get(topic)?.let { WcEncoding.hexDecode(it) }
    }

    fun deleteSymKey(topic: String) {
        keychain.delete(topic)
    }

    fun hasKey(tag: String): Boolean = keychain.has(tag)

    fun generateRandomBytes32(): ByteArray = CryptoRand.Default.nextBytes(ByteArray(32))

    fun encodeEnvelope(topic: String, payloadJson: String): String {
        val symKey = getSymKey(topic)
            ?: throw WcProtocolException("No symmetric key for topic: $topic")
        val iv = CryptoRand.Default.nextBytes(ByteArray(12))
        val sealed = chachaSeal(symKey, iv, payloadJson.encodeToByteArray())
        val envelope = ByteArray(1 + 12 + sealed.size)
        envelope[0] = 0x00
        iv.copyInto(envelope, 1)
        sealed.copyInto(envelope, 13)
        return WcEncoding.base64Encode(envelope)
    }

    fun decodeEnvelope(topic: String, encoded: String): String {
        val bytes = WcEncoding.base64Decode(encoded)
        require(bytes.isNotEmpty()) { "Empty envelope" }
        val type = bytes[0].toInt()
        return when (type) {
            0 -> {
                val iv = bytes.copyOfRange(1, 13)
                val sealed = bytes.copyOfRange(13, bytes.size)
                val symKey = getSymKey(topic)
                    ?: throw WcProtocolException("No symmetric key for topic: $topic")
                chachaOpen(symKey, iv, sealed).decodeToString()
            }
            1 -> throw WcProtocolException("type-1 envelope not supported in this phase")
            2 -> bytes.copyOfRange(1, bytes.size).decodeToString()
            else -> throw WcProtocolException("Unknown envelope type: $type")
        }
    }

    internal fun encodeIss(publicKey: ByteArray): String {
        val multicodecBytes = ByteArray(2 + publicKey.size)
        multicodecBytes[0] = 0xED.toByte()
        multicodecBytes[1] = 0x01
        publicKey.copyInto(multicodecBytes, 2)
        return "did:key:z" + WcEncoding.base58btcEncode(multicodecBytes)
    }

    internal fun decodeIss(issuer: String): ByteArray {
        val parts = issuer.split(":")
        require(parts.size == 3) { "Invalid did:key format" }
        require(parts[0] == "did") { "Issuer must start with 'did'" }
        require(parts[1] == "key") { "Issuer must use method 'key'" }
        val multibase = parts[2]
        require(multibase.startsWith("z")) { "Issuer must use base58btc encoding (prefix 'z')" }
        val decoded = WcEncoding.base58btcDecode(multibase.substring(1))
        require(decoded.size >= 2) { "Decoded multicodec too short" }
        require(decoded[0].toInt() and 0xFF == 0xED && decoded[1].toInt() and 0xFF == 0x01) {
            "Issuer multicodec header must be Ed25519 (0xED01)"
        }
        require(decoded.size == 34) { "Ed25519 public key must be 32 bytes, got ${decoded.size - 2}" }
        return decoded.copyOfRange(2, 34)
    }

    internal fun sha256(data: ByteArray): ByteArray = sha256Hasher.hashBlocking(data)

    internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val hmacKey = cryptoProvider.get(HMAC)
            .keyDecoder(SHA256)
            .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
        return hmacKey.signatureGenerator().generateSignatureBlocking(data)
    }

    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(effectiveSalt, ikm)
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val input = ByteArray(t.size + info.size + 1)
            t.copyInto(input, 0)
            info.copyInto(input, t.size)
            input[input.size - 1] = counter.toByte()
            t = hmacSha256(prk, input)
            val toCopy = minOf(t.size, length - pos)
            t.copyInto(okm, pos, 0, toCopy)
            pos += toCopy
            counter++
        }
        return okm
    }

    @OptIn(DelicateCryptographyApi::class)
    internal fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val chachaKey = cryptoProvider.get(ChaCha20Poly1305)
            .keyDecoder()
            .decodeFromByteArrayBlocking(ChaCha20Poly1305.Key.Format.RAW, key)
        return chachaKey.cipher().encryptWithIvBlocking(nonce, plaintext, ByteArray(0))
    }

    @OptIn(DelicateCryptographyApi::class)
    internal fun chachaOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray {
        val chachaKey = cryptoProvider.get(ChaCha20Poly1305)
            .keyDecoder()
            .decodeFromByteArrayBlocking(ChaCha20Poly1305.Key.Format.RAW, key)
        return chachaKey.cipher().decryptWithIvBlocking(nonce, sealed, ByteArray(0))
    }

    companion object {
        private const val CLIENT_SEED_TAG = "client_seed"
    }
}

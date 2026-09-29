package com.ultrabytecoder.coinsafebox.providers.ton

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.providers.DerivationPathResolver
import com.ultrabytecoder.coinsafebox.providers.ton.boc.*
import com.ultrabytecoder.coinsafebox.providers.ton.wallet.WalletContract
import com.ultrabytecoder.coinsafebox.providers.ton.wallet.WalletContractV3R2
import com.ultrabytecoder.coinsafebox.providers.ton.wallet.WalletContractV4
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.SHA512
import io.github.andreypfau.curve25519.ed25519.Ed25519
import io.github.andreypfau.curve25519.ed25519.Ed25519PrivateKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.core.toByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class TonWalletVersion {
    V3R1, V3R2, V4R1, V4R2
}

abstract class TonBase(
    protected val masterSeed: ByteArray?,
    protected val networkConfig: NetworkConfig
) {
    companion object {
        internal const val TON_WORKCHAIN = 0
        internal const val TON_COIN_TYPE = 607
        internal const val DEFAULT_WALLET_ID = 698983191
        internal const val PARAMS_KEY_WALLET_VERSION = "walletVersion"

        private val provider = CryptographyProvider.Default
        private val sha256Hasher = provider.get(SHA256).hasher()
        private val sha512Hasher = provider.get(SHA512).hasher()

        /** Parse wallet version from account params; defaults to V3R2. */
        fun parseWalletVersion(params: JsonObject): TonWalletVersion {
            val raw = params[PARAMS_KEY_WALLET_VERSION]?.jsonPrimitive?.content
                ?: TonWalletVersion.V3R2.name
            return TonWalletVersion.entries.find { it.name == raw }
                ?: TonWalletVersion.V3R2
        }

        /** Resolve a WalletContract instance for the given version. */
        fun walletContractFor(
            version: TonWalletVersion,
            workchain: Int,
            publicKey: ByteArray,
            walletId: Int? = null
        ): WalletContract = when (version) {
            TonWalletVersion.V3R1, TonWalletVersion.V3R2 ->
                WalletContractV3R2.create(workchain, publicKey, walletId)
            TonWalletVersion.V4R1, TonWalletVersion.V4R2 ->
                WalletContractV4.create(workchain, publicKey, walletId)
        }
    }

    data class Ed25519KeyPair(val privateKey: Ed25519PrivateKey, val publicKey: ByteArray, val privateKeySeed: ByteArray)

    protected fun deriveTonKey(index: Long): Ed25519KeyPair {
        val master = hmacSha512("ed25519 seed".toByteArray(), masterSeed!!)
        val key = deriveSlip10Path(master, listOf(
            hardenedIdx(44),
            hardenedIdx(TON_COIN_TYPE),
            hardenedIdx(index.toInt()),
        ))
        val privateKeySeed = key.copyOfRange(0, 32)
        val privateKey = Ed25519.keyFromSeed(privateKeySeed)
        val publicKeyBytes = privateKey.publicKey().toByteArray()
        return Ed25519KeyPair(
            privateKey = privateKey,
            publicKey = publicKeyBytes,
            privateKeySeed = privateKeySeed,
        )
    }

    protected fun deriveTonKeyFromPath(path: String): Ed25519KeyPair {
        val segments = DerivationPathResolver.parsePath(path)
        val slip10Indices = segments.map { (index, _) ->
            hardenedIdx(index.toInt())
        }
        val master = hmacSha512("ed25519 seed".toByteArray(), masterSeed!!)
        val key = deriveSlip10Path(master, slip10Indices)
        val privateKeySeed = key.copyOfRange(0, 32)
        val privateKey = Ed25519.keyFromSeed(privateKeySeed)
        val publicKeyBytes = privateKey.publicKey().toByteArray()
        return Ed25519KeyPair(
            privateKey = privateKey,
            publicKey = publicKeyBytes,
            privateKeySeed = privateKeySeed,
        )
    }

    protected fun tonAddressFromPublicKey(
        publicKey: ByteArray,
        version: TonWalletVersion = TonWalletVersion.V3R2,
        subwalletId: Int = DEFAULT_WALLET_ID,
        workchain: Int = TON_WORKCHAIN
    ): String {
        val wallet: WalletContract = when (version) {
            TonWalletVersion.V3R1, TonWalletVersion.V3R2 ->
                WalletContractV3R2.create(workchain, publicKey, subwalletId)
            TonWalletVersion.V4R1, TonWalletVersion.V4R2 ->
                WalletContractV4.create(workchain, publicKey, subwalletId)
        }
        return wallet.address.toString()
    }

    protected suspend fun tonGetBalance(client: HttpClient, address: String): Long {
        val response: HttpResponse = client.get("${networkConfig.tonApiBase}/getAddressBalance?address=$address") {
            contentType(ContentType.Application.Json)
        }
        val body = response.body<String>()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        return json["result"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
    }

    protected suspend fun tonGetAccountState(client: HttpClient, address: String): String {
        val response: HttpResponse = client.get("${networkConfig.tonApiBase}/getAddressState?address=$address") {
            contentType(ContentType.Application.Json)
        }
        val body = response.body<String>()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        return json["result"]?.jsonPrimitive?.content ?: "nonexist"
    }

    protected suspend fun tonGetSeqno(client: HttpClient, address: String): Int {
        val response: HttpResponse = client.get("${networkConfig.tonApiBase}/getWalletInformation?address=$address") {
            contentType(ContentType.Application.Json)
        }
        val body = response.body<String>()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        val result = json["result"]?.jsonObject ?: return 0
        return result["seqno"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    }

    protected suspend fun tonSendBoc(client: HttpClient, bocBase64: String): String {
        val response: HttpResponse = client.post("${networkConfig.tonApiBase}/sendBocReturnHash") {
            contentType(ContentType.Application.Json)
            setBody("""{"boc":"$bocBase64"}""")
        }
        val body = response.body<String>()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        if (json.containsKey("error")) {
            throw RuntimeException("TON broadcast failed: ${json["error"]}")
        }
        return json["result"]!!.jsonObject["hash"]!!.jsonPrimitive.content
    }

    protected fun signTonMessage(message: ByteArray, keyPair: Ed25519KeyPair): ByteArray =
        keyPair.privateKey.sign(message)

    protected fun sha256(data: ByteArray): ByteArray = sha256Hasher.hashBlocking(data)

    private fun sha512(data: ByteArray): ByteArray = sha512Hasher.hashBlocking(data)

    protected fun hmacSha512(key: ByteArray, message: ByteArray): ByteArray {
        val blockLen = 128
        val paddedKey = if (key.size > blockLen) {
            sha512(key).copyOf(blockLen)
        } else {
            key.copyOf(blockLen)
        }
        val ipad = ByteArray(blockLen) { i -> (paddedKey[i].toInt() xor 0x36).toByte() }
        val opad = ByteArray(blockLen) { i -> (paddedKey[i].toInt() xor 0x5c).toByte() }
        return sha512(opad + sha512(ipad + message))
    }

    protected fun deriveSlip10Path(masterKey: ByteArray, path: List<Long>): ByteArray {
        var key = masterKey
        for (index in path) {
            val il = key.copyOfRange(0, 32)
            val ir = key.copyOfRange(32, 64)
            val data = byteArrayOf(0x00) + il + serializeIndex(index)
            key = hmacSha512(ir, data)
        }
        return key
    }

    fun hardenedIdx(i: Int): Long = i.toLong() or (1L shl 31)

    private fun serializeIndex(index: Long): ByteArray = byteArrayOf(
        (index shr 24).toByte(), (index shr 16).toByte(),
        (index shr 8).toByte(), index.toByte()
    )

    protected fun base64Encode(data: ByteArray): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val result = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            val remaining = data.size - i
            result.append(chars[(triple shr 18) and 0x3F])
            result.append(chars[(triple shr 12) and 0x3F])
            if (remaining >= 2) result.append(chars[(triple shr 6) and 0x3F]) else result.append('=')
            if (remaining >= 3) result.append(chars[triple and 0x3F]) else result.append('=')
            i += 3
        }
        return result.toString()
    }

    protected fun base64UrlDecode(str: String): ByteArray {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val lookup = IntArray(128) { -1 }
        for (i in chars.indices) lookup[chars[i].code] = i
        val buf = mutableListOf<Byte>()
        var accum = 0
        var bits = 0
        for (c in str) {
            val idx = lookup[c.code]
            if (idx < 0) continue
            accum = (accum shl 6) or idx
            bits += 6
            if (bits >= 8) {
                bits -= 8
                buf.add(((accum shr bits) and 0xFF).toByte())
            }
        }
        return buf.toByteArray()
    }
}

private val kotlinx.serialization.json.JsonElement.jsonObject get() = this as kotlinx.serialization.json.JsonObject
private val kotlinx.serialization.json.JsonElement.jsonPrimitive get() = this as kotlinx.serialization.json.JsonPrimitive

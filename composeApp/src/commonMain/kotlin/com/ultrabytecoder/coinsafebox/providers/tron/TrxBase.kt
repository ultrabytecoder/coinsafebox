package com.ultrabytecoder.coinsafebox.providers

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.providers.DerivationPathResolver
import fr.acinq.bitcoin.Base58Check
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

abstract class TrxBase(
    protected val masterKey: DeterministicWallet.ExtendedPrivateKey,
    protected val networkConfig: NetworkConfig
) {

    protected fun deriveTrxKey(accountIndex: Long): DeterministicWallet.ExtendedPrivateKey {
        return masterKey.derivePrivateKey(
            listOf(
                DeterministicWallet.hardened(44),
                DeterministicWallet.hardened(195),
                DeterministicWallet.hardened(accountIndex),
                0L,
                0L
            )
        )
    }

    protected fun deriveTrxKeyFromPath(path: String): DeterministicWallet.ExtendedPrivateKey {
        val segments = DerivationPathResolver.parsePath(path).map { (index, hardened) ->
            if (hardened) DeterministicWallet.hardened(index) else index
        }
        return masterKey.derivePrivateKey(segments)
    }

    protected fun trxAddressFromDerivedKey(key: DeterministicWallet.ExtendedPrivateKey): String {
        return trxAddressFromPublicKey(key.publicKey)
    }

    protected fun findRecoveryId(
        sig: fr.acinq.bitcoin.ByteVector64,
        msgHash: ByteArray,
        expectedPubKey: PublicKey
    ): Int {
        for (recId in 0..1) {
            try {
                val recovered = Crypto.recoverPublicKey(sig, msgHash, recId)
                if (recovered == expectedPubKey) return recId
            } catch (_: Exception) {
                continue
            }
        }
        throw IllegalStateException("Could not determine recovery ID")
    }

    protected fun signTronTransaction(txidBytes: ByteArray, fromKey: DeterministicWallet.ExtendedPrivateKey): String {
        val sig = Crypto.sign(txidBytes, fromKey.privateKey)
        val recoveryId = findRecoveryId(sig, txidBytes, fromKey.publicKey)
        val sigBytes = sig.toByteArray()
        val r = sigBytes.copyOfRange(0, 32)
        val s = sigBytes.copyOfRange(32, 64)
        val signedSig = r + s + byteArrayOf(recoveryId.toByte())
        return Hex.encode(signedSig)
    }

    protected suspend fun broadcastSignedTransaction(
        client: HttpClient,
        broadcastBody: String
    ): String {
        val broadcastResponse: HttpResponse = client.post("${networkConfig.tronApiBase}/wallet/broadcasttransaction") {
            contentType(ContentType.Application.Json)
            setBody(broadcastBody)
        }
        val body = broadcastResponse.body<String>()
        val json = Json.parseToJsonElement(body).jsonObject
        val result = json["result"]?.jsonPrimitive?.content == "true"
        if (!result) {
            throw RuntimeException("TRON broadcast failed: $body")
        }
        return json["txid"]?.jsonPrimitive?.content ?: ""
    }

    protected suspend fun tronTriggerSmartContract(
        client: HttpClient,
        contractAddress: String,
        functionSelector: String,
        parameter: String,
        ownerAddress: String,
        feeLimit: Long = 0L
    ): JsonObject {
        val feeLimitJson = if (feeLimit > 0) ""","fee_limit":$feeLimit""" else ""
        val body = """{"contract_address":"$contractAddress","function_selector":"$functionSelector","parameter":"$parameter","owner_address":"$ownerAddress","visible":true$feeLimitJson}"""
        val response: HttpResponse = client.post("${networkConfig.tronApiBase}/wallet/triggersmartcontract") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val responseBody = response.body<String>()
        return Json.parseToJsonElement(responseBody).jsonObject
    }

    protected fun encodeTronAddressParameter(base58Address: String): String {
        val (_, payload) = Base58Check.decode(base58Address)
        return payload.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.padStart(64, '0')
    }
}
